package io.github.atengk.boot.ssh.verifier;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.config.keys.KeyUtils;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于纯内存的云原生 SSH 服务端主机公钥指纹校验器
 * <p>
 * 100% 运行于内存中，坚决绝不尝试向本地磁盘（如 {@code ~/.ssh/known_hosts}）写入任何文件，
 * 彻底根除在 Kubernetes 只读根文件系统（Read-Only Rootfs）与非 root 容器中因写入权限不足导致的崩溃报错。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class InMemoryServerKeyVerifier implements ServerKeyVerifier {

    /**
     * 目标真实服务器路由键属性标识（专职服务于 ProxyJump 跳板机穿透场景，避免按动态代理端口记录指纹）
     */
    public static final AttributeKey<String> TARGET_HOST_KEY = new AttributeKey<>();

    /**
     * 会话级动态主机公钥策略属性标识（允许每个独立连接或服务器描述符覆盖全局默认策略）
     */
    public static final AttributeKey<HostKeyPolicy> HOST_KEY_POLICY_KEY = new AttributeKey<>();

    @Getter
    private final HostKeyPolicy policy;

    /**
     * 内存中存储的已知主机公钥缓存 (key: "host:port", value: PublicKey)
     */
    private final ConcurrentHashMap<String, PublicKey> knownKeys = new ConcurrentHashMap<>();

    /**
     * 默认构造函数，使用宽容自适应模式（LENIENT）
     */
    public InMemoryServerKeyVerifier() {
        this(HostKeyPolicy.LENIENT);
    }

    /**
     * 指定安全校验策略的构造函数
     *
     * @param policy 主机公钥指纹校验策略
     */
    public InMemoryServerKeyVerifier(HostKeyPolicy policy) {
        this.policy = policy != null ? policy : HostKeyPolicy.LENIENT;
    }

    @Override
    public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
        if (serverKey == null) {
            log.warn("ateng-boot-ssh: 服务端返回的主机公钥为 null，拒绝连接");
            return false;
        }

        // 优先支持会话级上下文动态覆盖主机公钥校验策略
        HostKeyPolicy effectivePolicy = this.policy;
        if (clientSession != null) {
            HostKeyPolicy sessionPolicy = clientSession.getAttribute(HOST_KEY_POLICY_KEY);
            if (sessionPolicy != null) {
                effectivePolicy = sessionPolicy;
            }
        }

        if (effectivePolicy == HostKeyPolicy.IGNORE) {
            log.debug("ateng-boot-ssh: HostKeyPolicy 为 IGNORE，跳过服务端主机指纹校验: {}", remoteAddress);
            return true;
        }

        String hostPortKey = resolveAddressKey(remoteAddress, clientSession);

        if (effectivePolicy == HostKeyPolicy.LENIENT) {
            PublicKey existingKey = knownKeys.putIfAbsent(hostPortKey, serverKey);
            if (existingKey == null) {
                // 首次连接，宽容信任并存入内存
                log.info("ateng-boot-ssh: 首次连接目标主机 [{}], 宽容信任并记录纯内存公钥指纹: [{}]",
                        hostPortKey, KeyUtils.getFingerPrint(serverKey));
                return true;
            }
            // 曾经连接过，比对公钥
            if (KeyUtils.compareKeys(existingKey, serverKey)) {
                return true;
            }
            // 公钥不匹配！可能存在中间人攻击 (MITM)
            log.error("ateng-boot-ssh: 主机公钥指纹发生变更！疑似中间人攻击 (MITM) 阻断连接. 目标主机: [{}], 内存指纹: [{}], 服务端返回指纹: [{}]",
                    hostPortKey, KeyUtils.getFingerPrint(existingKey), KeyUtils.getFingerPrint(serverKey));
            return false;
        }

        if (effectivePolicy == HostKeyPolicy.STRICT) {
            PublicKey existingKey = knownKeys.get(hostPortKey);
            if (existingKey != null && KeyUtils.compareKeys(existingKey, serverKey)) {
                return true;
            }
            log.error("ateng-boot-ssh: STRICT 严格校验阻断！目标主机: [{}], 未在已知指纹名单中或指纹不匹配: [{}]",
                    hostPortKey, KeyUtils.getFingerPrint(serverKey));
            return false;
        }

        return false;
    }

    /**
     * 手动预注册已知主机公钥
     *
     * @param host 目标主机名或 IP
     * @param port 端口
     * @param key  主机公钥
     */
    public void registerKnownKey(String host, int port, PublicKey key) {
        registerKnownKey(host + ":" + port, key);
    }

    /**
     * 手动预注册已知主机公钥
     *
     * @param hostPortKey 主机端口键值 (host:port)
     * @param key         主机公钥
     */
    public void registerKnownKey(String hostPortKey, PublicKey key) {
        if (hostPortKey != null && key != null) {
            knownKeys.put(hostPortKey, key);
        }
    }

    /**
     * 获取指定主机的已知公钥
     *
     * @param hostPortKey 主机端口键值
     * @return 已知公钥，若无记录返回 null
     */
    public PublicKey getKnownKey(String hostPortKey) {
        return hostPortKey != null ? knownKeys.get(hostPortKey) : null;
    }

    /**
     * 获取当前纯内存已知公钥只读映射表
     *
     * @return 只读映射表
     */
    public Map<String, PublicKey> getKnownKeys() {
        return Collections.unmodifiableMap(knownKeys);
    }

    /**
     * 清理所有内存中记录的已知指纹缓存
     */
    public void clear() {
        knownKeys.clear();
    }

    /**
     * 解析网络套接字地址字符串
     *
     * @param remoteAddress 网络地址
     * @param session       客户端会话保底
     * @return "host:port" 格式字符串
     */
    public static String resolveAddressKey(SocketAddress remoteAddress, ClientSession session) {
        if (session != null) {
            String target = session.getAttribute(TARGET_HOST_KEY);
            if (target != null && !target.isBlank()) {
                return target;
            }
        }
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            return inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort();
        }
        if (remoteAddress != null) {
            return remoteAddress.toString();
        }
        if (session != null && session.getConnectAddress() instanceof InetSocketAddress inet) {
            return inet.getHostString() + ":" + inet.getPort();
        }
        return "unknown:22";
    }
}
