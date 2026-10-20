package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.http.properties.HttpPoolProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpProxyProperties;
import io.github.atengk.boot.http.properties.HttpSslProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * JDK 17 原生 HttpClient 物理连接工厂
 * 基于 java.net.http.HttpClient 提供零外部网络库依赖、虚拟线程友好的优雅保底实现
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public final class JdkHttpClientFactory {

    private JdkHttpClientFactory() {
    }

    /**
     * 根据配置构建 JdkClientHttpRequestFactory
     *
     * @param properties 配置属性
     * @return ClientHttpRequestFactory 物理请求工厂
     */
    public static ClientHttpRequestFactory createFactory(HttpProperties properties) {
        HttpPoolProperties pool = properties.getPool();
        HttpSslProperties ssl = properties.getSsl();
        HttpProxyProperties proxy = properties.getProxy();

        HttpClient.Builder builder = HttpClient.newBuilder();

        // 1. 建联超时配置
        if (pool.getConnectTimeout() != null) {
            builder.connectTimeout(pool.getConnectTimeout());
        }

        // 2. SSL Insecure 信任全部证书支持
        if (ssl.isInsecure()) {
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }

                            @Override
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            }
                        }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                builder.sslContext(sslContext);
                log.info("JDK HttpClient 已启用 Insecure SSL 模式，信任所有证书");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("构建 JDK Insecure SSLContext 失败", e);
            }
        }

        // 3. 出口正向代理支持
        if (proxy.isEnabled() && StringUtils.hasText(proxy.getHost())) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            if (StringUtils.hasText(proxy.getUsername())) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                proxy.getUsername(),
                                proxy.getPassword() != null ? proxy.getPassword().toCharArray() : new char[0]
                        );
                    }
                });
            }
            log.info("JDK HttpClient 已启用出口代理: {}:{}", proxy.getHost(), proxy.getPort());
        }

        HttpClient httpClient = builder.build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        if (pool.getReadTimeout() != null) {
            factory.setReadTimeout(pool.getReadTimeout());
        }

        log.info("JDK 17 原生 HttpClient 工厂初始化成功 [connectTimeout={}, readTimeout={}]",
                pool.getConnectTimeout(), pool.getReadTimeout());
        return factory;
    }
}
