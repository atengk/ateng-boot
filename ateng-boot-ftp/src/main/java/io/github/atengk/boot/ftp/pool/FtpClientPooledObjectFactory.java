package io.github.atengk.boot.ftp.pool;

import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import io.github.atengk.boot.ftp.enums.FtpsDataChannelProtection;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.properties.FtpsProperties;
import io.github.atengk.boot.ftp.registry.FtpClientRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.KeyManagerUtils;
import org.apache.commons.net.util.TrustManagerUtils;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Apache Commons Pool2 的 FTP/FTPS 客户端受管对象池工厂
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Getter
public class FtpClientPooledObjectFactory extends BasePooledObjectFactory<FTPClient> {

    private final String serverName;
    private final FtpProperties properties;
    private final List<FtpClientCustomizer> customizers;

    /**
     * 单节点默认构造器（向后兼容）
     *
     * @param properties 节点配置属性
     */
    public FtpClientPooledObjectFactory(FtpProperties properties) {
        this(FtpClientRegistry.DEFAULT_SERVER, properties, Collections.emptyList());
    }

    /**
     * 含节点名称与定制器扩展链的全参构造器
     *
     * @param serverName  服务节点名称
     * @param properties  节点配置属性
     * @param customizers 客户端定制器扩展链
     */
    public FtpClientPooledObjectFactory(String serverName,
                                        FtpProperties properties,
                                        List<FtpClientCustomizer> customizers) {
        this.serverName = StringUtils.hasText(serverName) ? serverName.trim() : FtpClientRegistry.DEFAULT_SERVER;
        this.properties = properties;
        this.customizers = customizers != null ? List.copyOf(customizers) : Collections.emptyList();
    }

    @Override
    public FTPClient create() throws Exception {
        FTPClient client = createClientInstance();
        try {
            configureClient(client);
            connectAndLogin(client);
            postLoginSetup(client);
            applyCustomizers(client);
            log.debug("成功创建并初始化 FTP 客户端连接 [server={}]: {}:{}",
                    serverName, properties.getHost(), properties.getPort());
            return client;
        } catch (Exception e) {
            safeDisconnect(client);
            if (e instanceof FtpException) {
                throw e;
            }
            throw new FtpException(FtpResultCode.CONNECT_FAILED, "创建 FTP 客户端连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public PooledObject<FTPClient> wrap(FTPClient ftpClient) {
        return new DefaultPooledObject<>(ftpClient);
    }

    @Override
    public boolean validateObject(PooledObject<FTPClient> p) {
        FTPClient client = p.getObject();
        if (client == null || !client.isConnected() || !client.isAvailable()) {
            return false;
        }
        try {
            return client.sendNoOp();
        } catch (Exception e) {
            log.debug("FTP 连接探针校验失败 (NOOP 失败): {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void activateObject(PooledObject<FTPClient> p) throws Exception {
        resetClientState(p.getObject(), "借出");
    }

    @Override
    public void passivateObject(PooledObject<FTPClient> p) throws Exception {
        resetClientState(p.getObject(), "归还");
    }

    @Override
    public void destroyObject(PooledObject<FTPClient> p) {
        FTPClient client = p.getObject();
        safeDisconnect(client);
    }

    FTPClient createClientInstance() {
        FtpsProperties ftps = properties.getFtps();
        if (ftps != null && ftps.isEnabled()) {
            String protocol = StringUtils.hasText(ftps.getProtocol()) ? ftps.getProtocol().trim() : "TLSv1.3";
            boolean implicit = ftps.isImplicit();
            FTPSClient ftpsClient = new FTPSClient(protocol, implicit);
            configureFtpsCertificates(ftpsClient, ftps);
            return ftpsClient;
        }
        return new FTPClient();
    }

    private void resetClientState(FTPClient client, String phase) {
        if (client == null || !client.isConnected()) {
            return;
        }
        try {
            // 强制重置回根目录，彻底杜绝多线程高并发下的串目录灾难
            boolean cwdSuccess = client.changeWorkingDirectory("/");
            if (!cwdSuccess) {
                throw new FtpException(FtpResultCode.OPERATION_FAILED,
                        "FTP 连接" + phase + "连接池重置根目录失败，服务端响应码: " + client.getReplyCode());
            }
            // 复位传输数据类型为配置的默认值
            applyFileType(client);
        } catch (FtpException e) {
            throw e;
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.OPERATION_FAILED,
                    "FTP 连接" + phase + "连接池重置状态失败: " + e.getMessage(), e);
        }
    }

    private void configureFtpsCertificates(FTPSClient ftpsClient, FtpsProperties ftps) {
        try {
            if (ftps.isTrustAll()) {
                ftpsClient.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
            } else if (StringUtils.hasText(ftps.getTrustStorePath())) {
                KeyStore trustStore = loadKeyStore(ftps.getTrustStorePath(), ftps.getTrustStorePassword());
                ftpsClient.setTrustManager(TrustManagerUtils.getDefaultTrustManager(trustStore));
            }

            if (StringUtils.hasText(ftps.getKeyStorePath())) {
                KeyStore keyStore = loadKeyStore(ftps.getKeyStorePath(), ftps.getKeyStorePassword());
                String keyPassword = ftps.getKeyStorePassword() != null ? ftps.getKeyStorePassword() : "";
                ftpsClient.setKeyManager(KeyManagerUtils.createClientKeyManager(keyStore, null, keyPassword));
            }
        } catch (FtpException e) {
            throw e;
        } catch (Exception e) {
            throw new FtpException(FtpResultCode.CONNECT_FAILED,
                    "加载 FTPS 证书库失败 [server=" + serverName + "]: " + e.getMessage(), e);
        }
    }

    private KeyStore loadKeyStore(String storePath, String storePassword) throws Exception {
        Resource resource = resolveResource(storePath.trim());
        if (!resource.exists()) {
            throw new FtpException(FtpResultCode.INVALID_PATH, "FTPS 证书库文件不存在: " + storePath);
        }
        char[] pwdChars = storePassword != null ? storePassword.toCharArray() : new char[0];
        String lowerPath = storePath.trim().toLowerCase();
        String storeType = lowerPath.endsWith(".jks") ? "JKS" : "PKCS12";
        KeyStore keyStore = KeyStore.getInstance(storeType);
        try (InputStream in = resource.getInputStream()) {
            keyStore.load(in, pwdChars);
        }
        return keyStore;
    }

    private Resource resolveResource(String location) {
        if (!location.startsWith("classpath:") && !location.startsWith("file:")) {
            File localFile = new File(location);
            if (localFile.exists()) {
                return new FileSystemResource(localFile);
            }
        }
        return new DefaultResourceLoader().getResource(location);
    }

    private void configureClient(FTPClient client) {
        if (StringUtils.hasText(properties.getControlEncoding())) {
            client.setControlEncoding(properties.getControlEncoding());
        }
        if (StringUtils.hasText(properties.getServerTimeZone())) {
            FTPClientConfig clientConfig = new FTPClientConfig();
            clientConfig.setServerTimeZoneId(properties.getServerTimeZone().trim());
            client.configure(clientConfig);
        }
        if (properties.getConnectTimeout() != null) {
            client.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        }
        if (properties.getDataTimeout() != null) {
            client.setDefaultTimeout((int) properties.getDataTimeout().toMillis());
            client.setDataTimeout((int) properties.getDataTimeout().toMillis());
        }
        if (properties.getControlKeepAliveTimeout() != null && !properties.getControlKeepAliveTimeout().isZero()) {
            client.setControlKeepAliveTimeout(properties.getControlKeepAliveTimeout().toSeconds());
        }
        if (properties.getBufferSize() != null) {
            client.setBufferSize((int) properties.getBufferSize().toBytes());
        }
    }

    private void connectAndLogin(FTPClient client) throws IOException {
        client.connect(properties.getHost(), properties.getPort());
        int replyCode = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            throw new FtpException(FtpResultCode.CONNECT_FAILED, "FTP 服务端拒绝连接，返回响应码: " + replyCode);
        }

        boolean loginSuccess = client.login(properties.getUsername(), properties.getPassword());
        if (!loginSuccess) {
            throw new FtpException(FtpResultCode.AUTH_FAILED, "FTP 鉴权登录失败，用户: " + properties.getUsername());
        }
    }

    private void postLoginSetup(FTPClient client) throws IOException {
        // 网络被动/主动模式与 NAT 假冒 IP 穿透策略
        if (FtpMode.PASSIVE.equals(properties.getMode())) {
            client.enterLocalPassiveMode();
            client.setPassiveNatWorkaroundStrategy(new FTPClient.NatServerResolverImpl(client));
        } else {
            client.enterLocalActiveMode();
        }

        // 传输数据类型
        applyFileType(client);

        // FTPS 数据通道保护协议 (PROT P / PROT C)
        if (client instanceof FTPSClient ftpsClient) {
            ftpsClient.execPBSZ(0);
            FtpsProperties ftps = properties.getFtps();
            if (ftps != null && FtpsDataChannelProtection.CLEAR.equals(ftps.getDataChannelProtection())) {
                ftpsClient.execPROT("C");
            } else {
                ftpsClient.execPROT("P");
            }
        }

        // 尝试向服务端协商开启 RFC 2640 UTF-8 扩展支持
        if (StandardCharsets.UTF_8.name().equalsIgnoreCase(properties.getControlEncoding())) {
            try {
                client.sendCommand("OPTS UTF8", "ON");
            } catch (Exception ignored) {
                // 部分传统老旧服务端可能不支持此指令，静默容错
            }
        }
    }

    private void applyCustomizers(FTPClient client) {
        if (customizers.isEmpty()) {
            return;
        }
        for (FtpClientCustomizer customizer : customizers) {
            if (customizer != null) {
                customizer.customize(serverName, client, properties);
            }
        }
    }

    private void applyFileType(FTPClient client) throws IOException {
        int targetType = FtpFileType.ASCII.equals(properties.getFileType())
                ? FTP.ASCII_FILE_TYPE
                : FTP.BINARY_FILE_TYPE;
        if (!client.setFileType(targetType)) {
            throw new FtpException(FtpResultCode.OPERATION_FAILED,
                    "FTP 设置传输数据类型失败，服务端响应码: " + client.getReplyCode());
        }
    }

    private void safeDisconnect(FTPClient client) {
        if (client != null && client.isConnected()) {
            try {
                client.logout();
            } catch (Exception ignored) {
            }
            try {
                client.disconnect();
            } catch (Exception ignored) {
            }
        }
    }
}
