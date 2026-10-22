package io.github.atengk.boot.ssh.model;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.properties.SshProperties;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.util.Assert;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;

/**
 * SSH 目标服务器连接不可变描述符
 * <p>
 * 专职支持从业务数据库（如 CMDB / 资产表）或微服务配置中心动态拉取连接信息，作为不可变值对象传入。
 * 原生涵盖主机、端口、用户名、多元凭据（密码/内存私钥/路径/KeyPair）、字符集、安全指纹策略与跳板机拓扑。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@EqualsAndHashCode(exclude = {"password", "privateKey", "passphrase"})
@Builder(toBuilder = true)
public final class SshServerInfo {

    /**
     * 目标服务器主机名或 IP 地址（必填）
     */
    private final String host;

    /**
     * 目标服务器 SSH 端口（默认 22）
     */
    @Builder.Default
    private final int port = 22;

    /**
     * 连接用户名（必填）
     */
    private final String username;

    /**
     * 登录口令密码
     */
    private final String password;

    /**
     * 纯内存私钥内容（支持 OpenSSH / PKCS#8 / PEM 纯文本，零磁盘临时文件）
     */
    private final String privateKey;

    /**
     * 本地磁盘私钥文件路径
     */
    private final String privateKeyPath;

    /**
     * 加密私钥的密码短语
     */
    private final String passphrase;

    /**
     * 预解析密钥对
     */
    private final KeyPair keyPair;

    /**
     * 交互与远程命令执行字符集（默认 UTF-8）
     */
    @Builder.Default
    private final Charset charset = StandardCharsets.UTF_8;

    /**
     * 主机公钥指纹校验策略（默认宽容自适应内存校验）
     */
    @Builder.Default
    private final HostKeyPolicy hostKeyPolicy = HostKeyPolicy.LENIENT;

    /**
     * 网络 TCP 连接与协议协商超时阈值
     */
    @Builder.Default
    private final Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 用户凭据身份鉴权认证超时阈值
     */
    @Builder.Default
    private final Duration authTimeout = Duration.ofSeconds(15);

    /**
     * 远程命令执行超时阈值
     */
    @Builder.Default
    private final Duration commandTimeout = Duration.ofSeconds(60);

    /**
     * 跳板机 / 堡垒机连接目标描述符（支持 ProxyJump 级联穿透）
     */
    private final SshServerInfo jumpHost;

    /**
     * 全参构造器（附带前置防御性校验与合理保底默认值）
     */
    public SshServerInfo(String host,
                         int port,
                         String username,
                         String password,
                         String privateKey,
                         String privateKeyPath,
                         String passphrase,
                         KeyPair keyPair,
                         Charset charset,
                         HostKeyPolicy hostKeyPolicy,
                         Duration connectTimeout,
                         Duration authTimeout,
                         Duration commandTimeout,
                         SshServerInfo jumpHost) {
        Assert.hasText(host, "host must not be blank");
        Assert.isTrue(port > 0 && port <= 65535, "port must be between 1 and 65535");
        Assert.hasText(username, "username must not be blank");

        this.host = host.trim();
        this.port = port;
        this.username = username.trim();
        this.password = password;
        this.privateKey = privateKey;
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
        this.keyPair = keyPair;
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        this.hostKeyPolicy = hostKeyPolicy != null ? hostKeyPolicy : HostKeyPolicy.LENIENT;
        this.connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(10);
        this.authTimeout = authTimeout != null ? authTimeout : Duration.ofSeconds(15);
        this.commandTimeout = commandTimeout != null ? commandTimeout : Duration.ofSeconds(60);
        this.jumpHost = jumpHost;
    }

    /**
     * 快速构建口令认证的服务器描述符（默认端口 22）
     *
     * @param host     主机名或 IP
     * @param username 用户名
     * @param password 密码
     * @return 不可变描述符
     */
    public static SshServerInfo of(String host, String username, String password) {
        return of(host, 22, username, password);
    }

    /**
     * 快速构建口令认证的服务器描述符
     *
     * @param host     主机名或 IP
     * @param port     SSH 端口
     * @param username 用户名
     * @param password 密码
     * @return 不可变描述符
     */
    public static SshServerInfo of(String host, int port, String username, String password) {
        return SshServerInfo.builder()
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * 从配置属性对象转换构建描述符
     *
     * @param properties 配置属性
     * @return 不可变描述符
     */
    public static SshServerInfo fromProperties(SshProperties properties) {
        if (properties == null) {
            return null;
        }
        return SshServerInfo.builder()
                .host(properties.getHost())
                .port(properties.getPort() > 0 ? properties.getPort() : 22)
                .username(properties.getUsername())
                .password(properties.getPassword())
                .privateKey(properties.getPrivateKey())
                .privateKeyPath(properties.getPrivateKeyPath())
                .passphrase(properties.getPassphrase())
                .hostKeyPolicy(properties.getHostKeyPolicy() != null ? properties.getHostKeyPolicy() : HostKeyPolicy.LENIENT)
                .connectTimeout(properties.getConnectTimeout() != null ? properties.getConnectTimeout() : Duration.ofSeconds(10))
                .authTimeout(properties.getAuthTimeout() != null ? properties.getAuthTimeout() : Duration.ofSeconds(15))
                .commandTimeout(properties.getCommandTimeout() != null ? properties.getCommandTimeout() : Duration.ofSeconds(60))
                .build();
    }

    /**
     * 获取主机与端口键值（形如 "192.168.1.10:22"）
     *
     * @return 主机与端口字符串
     */
    public String toHostPortKey() {
        return host + ":" + port;
    }

    /**
     * 获取会话全局唯一键值（形如 "root@192.168.1.10:22"）
     *
     * @return 会话标识字符串
     */
    public String toSessionKey() {
        return username + "@" + host + ":" + port;
    }

    /**
     * 计算当前连接描述符在会话连接池中的唯一路由键值
     * <p>
     * 结合目标坐标、用户名、多元凭据哈希与跳板机拓扑，确保不同凭据或不同跳板机的会话绝对物理隔离。
     * </p>
     *
     * @return 会话池路由键值
     */
    public String toPoolKey() {
        int credHash = java.util.Objects.hash(password, privateKey, privateKeyPath, passphrase, keyPair);
        String base = username + "@" + host + ":" + port + "#" + Integer.toHexString(credHash);
        if (jumpHost != null) {
            base += "->via(" + jumpHost.toPoolKey() + ")";
        }
        return base;
    }

    /**
     * 是否配置了密码
     *
     * @return true 若密码非空
     */
    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    /**
     * 是否配置了纯内存私钥内容
     *
     * @return true 若私钥内容非空白
     */
    public boolean hasPrivateKey() {
        return privateKey != null && !privateKey.isBlank();
    }

    /**
     * 是否配置了本地私钥文件路径
     *
     * @return true 若路径非空白
     */
    public boolean hasPrivateKeyPath() {
        return privateKeyPath != null && !privateKeyPath.isBlank();
    }

    /**
     * 是否配置了密钥对对象
     *
     * @return true 若 KeyPair 非空
     */
    public boolean hasKeyPair() {
        return keyPair != null;
    }

    /**
     * 是否配置了跳板机
     *
     * @return true 若跳板机描述符非空
     */
    public boolean hasJumpHost() {
        return jumpHost != null;
    }

    @Override
    public String toString() {
        return "SshServerInfo(" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                ", password=" + (hasPassword() ? "******" : "null") +
                ", privateKey=" + (hasPrivateKey() ? "[PROTECTED_PEM]" : "null") +
                ", privateKeyPath=" + (hasPrivateKeyPath() ? "'" + privateKeyPath + '\'' : "null") +
                ", passphrase=" + (passphrase != null ? "******" : "null") +
                ", keyPair=" + (hasKeyPair() ? "[KEYPAIR_PROVIDED]" : "null") +
                ", charset=" + charset +
                ", hostKeyPolicy=" + hostKeyPolicy +
                ", connectTimeout=" + connectTimeout +
                ", authTimeout=" + authTimeout +
                ", commandTimeout=" + commandTimeout +
                ", jumpHost=" + (hasJumpHost() ? jumpHost.toSessionKey() : "null") +
                ')';
    }
}
