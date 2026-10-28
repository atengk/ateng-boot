package io.github.atengk.boot.tcp.ssl;

import io.github.atengk.boot.tcp.enums.ClientAuth;
import io.github.atengk.boot.tcp.properties.TcpSslProperties;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP SSL 上下文工厂单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpSslContextFactoryTest {

    @Test
    @DisplayName("未开启 SSL 时返回 null")
    void testDisabledReturnsNull() {
        TcpSslProperties props = new TcpSslProperties();
        props.setEnabled(false);

        assertThat(TcpSslContextFactory.buildServerSslContext(props)).isNull();
        assertThat(TcpSslContextFactory.buildClientSslContext(props)).isNull();
    }

    @Test
    @DisplayName("构建服务端 SSLContext 成功 (单向 TLS)")
    void testBuildServerSslContextSuccess() {
        TcpSslProperties props = new TcpSslProperties();
        props.setEnabled(true);
        props.setKeyStore("classpath:ssl/server.p12");
        props.setKeyStorePassword("123456");
        props.setKeyStoreType("PKCS12");
        props.setClientAuth(ClientAuth.NONE);

        SslContext sslContext = TcpSslContextFactory.buildServerSslContext(props);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    @DisplayName("构建服务端 SSLContext 成功 (mTLS 双向强认证)")
    void testBuildServerMtlsContextSuccess() {
        TcpSslProperties props = new TcpSslProperties();
        props.setEnabled(true);
        props.setKeyStore("classpath:ssl/server.p12");
        props.setKeyStorePassword("123456");
        props.setKeyStoreType("PKCS12");
        props.setTrustStore("classpath:ssl/server-truststore.p12");
        props.setTrustStorePassword("123456");
        props.setTrustStoreType("PKCS12");
        props.setClientAuth(ClientAuth.NEED);

        SslContext sslContext = TcpSslContextFactory.buildServerSslContext(props);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    @DisplayName("服务端开启 SSL 但未配置 keyStore 时抛出异常")
    void testServerMissingKeyStoreThrows() {
        TcpSslProperties props = new TcpSslProperties();
        props.setEnabled(true);

        assertThatThrownBy(() -> TcpSslContextFactory.buildServerSslContext(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置有效");
    }

    @Test
    @DisplayName("构建客户端 SSLContext 成功 (加载信任库)")
    void testBuildClientSslContextSuccess() {
        TcpSslProperties props = new TcpSslProperties();
        props.setEnabled(true);
        props.setTrustStore("classpath:ssl/client-truststore.p12");
        props.setTrustStorePassword("123456");
        props.setTrustStoreType("PKCS12");

        SslContext sslContext = TcpSslContextFactory.buildClientSslContext(props);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    @DisplayName("构建客户端 SSLContext 成功 (mTLS 携带客户端私钥证书)")
    void testBuildClientMtlsContextSuccess() {
        TcpSslProperties props = new TcpSslProperties();
        props.setEnabled(true);
        props.setKeyStore("classpath:ssl/client.p12");
        props.setKeyStorePassword("123456");
        props.setKeyStoreType("PKCS12");
        props.setTrustStore("classpath:ssl/client-truststore.p12");
        props.setTrustStorePassword("123456");
        props.setTrustStoreType("PKCS12");

        SslContext sslContext = TcpSslContextFactory.buildClientSslContext(props);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }
}
