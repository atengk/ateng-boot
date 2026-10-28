package io.github.atengk.boot.tcp.ssl;

import io.github.atengk.boot.tcp.enums.ClientAuth;
import io.github.atengk.boot.tcp.properties.TcpSslProperties;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * TCP TLS/mTLS 链路加密与双向证书上下文工厂
 * <p>
 * 基于 Netty 原生 {@link SslContextBuilder} 实现配置驱动的 SSLContext 构建，
 * 支持 PKCS12 / JKS 密钥库及双向证书身份强校验（mTLS）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class TcpSslContextFactory {

    private static final Logger log = LoggerFactory.getLogger(TcpSslContextFactory.class);
    private static final ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    private TcpSslContextFactory() {
    }

    /**
     * 构建 TCP 服务端 SSL 上下文
     *
     * @param properties SSL 配置属性
     * @return Netty 服务端 SslContext，未开启时返回 null
     */
    public static SslContext buildServerSslContext(TcpSslProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return null;
        }

        try {
            KeyManagerFactory kmf = createKeyManagerFactory(
                    properties.getKeyStore(),
                    properties.getKeyStorePassword(),
                    properties.getKeyStoreType()
            );
            if (kmf == null) {
                throw new IllegalArgumentException("TCP 服务端已开启 TLS 链路加密，但未配置有效的 keyStore 证书密钥库路径");
            }

            SslContextBuilder builder = SslContextBuilder.forServer(kmf);

            // 客户端身份证书认证模式 (mTLS)
            ClientAuth clientAuth = properties.getClientAuth() != null ? properties.getClientAuth() : ClientAuth.NONE;
            if (clientAuth == ClientAuth.NEED) {
                builder.clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE);
            } else if (clientAuth == ClientAuth.WANT) {
                builder.clientAuth(io.netty.handler.ssl.ClientAuth.OPTIONAL);
            } else {
                builder.clientAuth(io.netty.handler.ssl.ClientAuth.NONE);
            }

            // 加载服务端信任库（用于校验客户端传入的证书）
            TrustManagerFactory tmf = createTrustManagerFactory(
                    properties.getTrustStore(),
                    properties.getTrustStorePassword(),
                    properties.getTrustStoreType()
            );
            if (tmf != null) {
                builder.trustManager(tmf);
            } else if (clientAuth == ClientAuth.NEED) {
                log.warn("TCP 服务端配置了 mTLS 双向认证 (clientAuth=NEED)，但未显式配置 trustStore，将使用系统默认 CA 信任库校验客户端证书");
            }

            log.info("TCP 服务端 TLS 链路加密引擎初始化成功: clientAuth={}", clientAuth);
            return builder.build();
        } catch (Exception ex) {
            throw new IllegalStateException("构建 TCP 服务端 SSL 上下文失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 构建 TCP 客户端 SSL 上下文
     *
     * @param properties SSL 配置属性
     * @return Netty 客户端 SslContext，未开启时返回 null
     */
    public static SslContext buildClientSslContext(TcpSslProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return null;
        }

        try {
            SslContextBuilder builder = SslContextBuilder.forClient();

            // 若配置了客户端证书（双向认证 mTLS）
            KeyManagerFactory kmf = createKeyManagerFactory(
                    properties.getKeyStore(),
                    properties.getKeyStorePassword(),
                    properties.getKeyStoreType()
            );
            if (kmf != null) {
                builder.keyManager(kmf);
            }

            // 加载客户端信任库（用于校验服务端证书）
            TrustManagerFactory tmf = createTrustManagerFactory(
                    properties.getTrustStore(),
                    properties.getTrustStorePassword(),
                    properties.getTrustStoreType()
            );
            if (tmf != null) {
                builder.trustManager(tmf);
            }

            log.info("TCP 客户端 TLS 链路加密引擎初始化成功: mTLS={}", kmf != null);
            return builder.build();
        } catch (Exception ex) {
            throw new IllegalStateException("构建 TCP 客户端 SSL 上下文失败: " + ex.getMessage(), ex);
        }
    }

    private static KeyManagerFactory createKeyManagerFactory(String keyStorePath, String password, String type) throws Exception {
        if (!StringUtils.hasText(keyStorePath)) {
            return null;
        }
        String storeType = StringUtils.hasText(type) ? type : "PKCS12";
        KeyStore ks = KeyStore.getInstance(storeType);
        char[] pwdChars = password != null ? password.toCharArray() : null;

        Resource resource = RESOURCE_LOADER.getResource(keyStorePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("未找到证书密钥库文件: " + keyStorePath);
        }
        try (InputStream in = resource.getInputStream()) {
            ks.load(in, pwdChars);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pwdChars);
        return kmf;
    }

    private static TrustManagerFactory createTrustManagerFactory(String trustStorePath, String password, String type) throws Exception {
        if (!StringUtils.hasText(trustStorePath)) {
            return null;
        }
        String storeType = StringUtils.hasText(type) ? type : "PKCS12";
        KeyStore ts = KeyStore.getInstance(storeType);
        char[] pwdChars = password != null ? password.toCharArray() : null;

        Resource resource = RESOURCE_LOADER.getResource(trustStorePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("未找到信任库文件: " + trustStorePath);
        }
        try (InputStream in = resource.getInputStream()) {
            ts.load(in, pwdChars);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf;
    }
}
