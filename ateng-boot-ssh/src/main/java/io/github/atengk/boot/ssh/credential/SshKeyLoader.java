package io.github.atengk.boot.ssh.credential;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * SSH 多元身份凭据与纯内存私钥解析加载器
 * <p>
 * 1. 深度适配业务数据库（CMDB / 资产表）存储的纯文本私钥字符串，100% 依托 RAM 纯内存流式解析，
 *    坚决绝不向宿主机磁盘落盘任何临时私钥文件，防范物理磁盘泄密；<br>
 * 2. 对称支持口令密码、纯内存 PEM 私钥、本地磁盘私钥路径与预解析 KeyPair 四种凭据形态；<br>
 * 3. 严格遵循因果异常链规范，将底层驱动解析异常受控转译为 {@link SshOperationException}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public final class SshKeyLoader {

    private static final String IN_MEMORY_RESOURCE_NAME = "in-memory-private-key";

    private SshKeyLoader() {
    }

    /**
     * 从纯内存 PEM / OpenSSH / PKCS#8 格式字符串中流式解析密钥对集合（零磁盘 I/O）
     *
     * @param pemContent 私钥字符串文本
     * @param passphrase 私钥加密口令（无加密可传 null）
     * @return 解析得到的密钥对列表（绝不返回 null）
     */
    public static List<KeyPair> loadKeyPairsFromMemory(String pemContent, String passphrase) {
        if (pemContent == null || pemContent.isBlank()) {
            return Collections.emptyList();
        }

        try {
            KeyPairResourceParser parser = SecurityUtils.getKeyPairResourceParser();
            FilePasswordProvider passwordProvider = createPasswordProvider(passphrase);
            NamedResource resource = NamedResource.ofName(IN_MEMORY_RESOURCE_NAME);

            Collection<KeyPair> keyPairs = parser.loadKeyPairs(null, resource, passwordProvider, pemContent);
            if (keyPairs == null || keyPairs.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(keyPairs);
        } catch (Exception e) {
            throw new SshOperationException(SshResultCode.AUTH_FAILED, "纯内存私钥内容解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从纯内存私钥内容中提取单个主密钥对
     *
     * @param pemContent 私钥字符串文本
     * @param passphrase 私钥加密口令
     * @return 密钥对
     */
    public static KeyPair loadKeyPairFromMemory(String pemContent, String passphrase) {
        List<KeyPair> keyPairs = loadKeyPairsFromMemory(pemContent, passphrase);
        if (keyPairs.isEmpty()) {
            throw new SshOperationException(SshResultCode.AUTH_FAILED, "未从提供的纯内存私钥内容中解析出有效密钥对");
        }
        return keyPairs.get(0);
    }

    /**
     * 从本地文件系统私钥文件中读取并解析密钥对集合
     *
     * @param pathString 私钥文件路径
     * @param passphrase 私钥加密口令
     * @return 解析得到的密钥对列表（绝不返回 null）
     */
    public static List<KeyPair> loadKeyPairsFromPath(String pathString, String passphrase) {
        if (pathString == null || pathString.isBlank()) {
            return Collections.emptyList();
        }
        return loadKeyPairsFromPath(Paths.get(pathString), passphrase);
    }

    /**
     * 从本地文件系统私钥文件中读取并解析密钥对集合
     *
     * @param path       私钥文件路径
     * @param passphrase 私钥加密口令
     * @return 解析得到的密钥对列表（绝不返回 null）
     */
    public static List<KeyPair> loadKeyPairsFromPath(Path path, String passphrase) {
        Assert.notNull(path, "private key path must not be null");
        if (!Files.isRegularFile(path)) {
            throw new SshOperationException(SshResultCode.AUTH_FAILED, "私钥文件不存在或不是常规文件: " + path);
        }

        try {
            KeyPairResourceParser parser = SecurityUtils.getKeyPairResourceParser();
            FilePasswordProvider passwordProvider = createPasswordProvider(passphrase);

            Collection<KeyPair> keyPairs = parser.loadKeyPairs(null, path, passwordProvider);
            if (keyPairs == null || keyPairs.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(keyPairs);
        } catch (Exception e) {
            throw new SshOperationException(SshResultCode.AUTH_FAILED, "本地私钥文件解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从本地文件系统私钥文件中提取单个主密钥对
     *
     * @param pathString 私钥文件路径
     * @param passphrase 私钥加密口令
     * @return 密钥对
     */
    public static KeyPair loadKeyPairFromPath(String pathString, String passphrase) {
        List<KeyPair> keyPairs = loadKeyPairsFromPath(pathString, passphrase);
        if (keyPairs.isEmpty()) {
            throw new SshOperationException(SshResultCode.AUTH_FAILED, "未从本地私钥文件中解析出有效密钥对: " + pathString);
        }
        return keyPairs.get(0);
    }

    /**
     * 将目标服务器描述符中的多元身份凭据统合注入至指定的 SSH 客户端会话
     *
     * @param session    当前客户端会话
     * @param serverInfo 目标服务器描述符
     */
    public static void applyCredentials(ClientSession session, SshServerInfo serverInfo) {
        Assert.notNull(session, "ClientSession must not be null");
        Assert.notNull(serverInfo, "SshServerInfo must not be null");

        // 1. 口令密码认证
        if (serverInfo.hasPassword()) {
            session.addPasswordIdentity(serverInfo.getPassword());
            log.debug("ateng-boot-ssh: 为会话 [{}] 注入口令密码身份凭据", serverInfo.toSessionKey());
        }

        // 2. 预解析 KeyPair 认证
        if (serverInfo.hasKeyPair()) {
            session.addPublicKeyIdentity(serverInfo.getKeyPair());
            log.debug("ateng-boot-ssh: 为会话 [{}] 注入预解析 KeyPair 身份凭据", serverInfo.toSessionKey());
        }

        // 3. 纯内存 PEM 私钥认证（零磁盘落盘）
        if (serverInfo.hasPrivateKey()) {
            List<KeyPair> memoryKeys = loadKeyPairsFromMemory(serverInfo.getPrivateKey(), serverInfo.getPassphrase());
            addIdentities(session, memoryKeys);
            log.debug("ateng-boot-ssh: 为会话 [{}] 注入纯内存私钥身份凭据 (共 {} 个)",
                    serverInfo.toSessionKey(), memoryKeys.size());
        }

        // 4. 本地文件私钥认证
        if (serverInfo.hasPrivateKeyPath()) {
            List<KeyPair> pathKeys = loadKeyPairsFromPath(serverInfo.getPrivateKeyPath(), serverInfo.getPassphrase());
            addIdentities(session, pathKeys);
            log.debug("ateng-boot-ssh: 为会话 [{}] 注入本地磁盘私钥身份凭据 (共 {} 个)",
                    serverInfo.toSessionKey(), pathKeys.size());
        }
    }

    private static void addIdentities(ClientSession session, List<KeyPair> keys) {
        if (keys != null) {
            for (KeyPair keyPair : keys) {
                session.addPublicKeyIdentity(keyPair);
            }
        }
    }

    private static FilePasswordProvider createPasswordProvider(String passphrase) {
        return (passphrase != null && !passphrase.isBlank())
                ? FilePasswordProvider.of(passphrase)
                : null;
    }
}
