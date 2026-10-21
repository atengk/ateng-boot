package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.customizer.HttpClient5Customizer;
import io.github.atengk.boot.http.options.RequestOptionsApplier;
import io.github.atengk.boot.http.properties.HttpPoolProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpProxyProperties;
import io.github.atengk.boot.http.properties.HttpSslProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;

/**
 * Apache HttpClient 5 生产级高并发池化连接工厂
 * 封装 PoolingHttpClientConnectionManager、后台空闲与过期连接自动驱逐、借出前保活探测及快速失败
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public final class ApacheHttpClient5Factory {

    private ApacheHttpClient5Factory() {
    }

    /**
     * 根据配置与扩展定制器构建 HttpComponentsClientHttpRequestFactory
     *
     * @param properties 配置属性
     * @param customizers 定制器列表
     * @return ClientHttpRequestFactory 物理请求工厂
     */
    public static ClientHttpRequestFactory createFactory(HttpProperties properties, List<HttpClient5Customizer> customizers) {
        HttpPoolProperties pool = properties.getPool();
        HttpSslProperties ssl = properties.getSsl();
        HttpProxyProperties proxy = properties.getProxy();

        // 1. 构建连接池管理器与核心参数
        PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(pool.getMaxConnections())
                .setMaxConnPerRoute(pool.getMaxPerRoute())
                .setValidateAfterInactivity(TimeValue.of(pool.getValidateAfterInactivity()));

        // 2. TCP 建联超时配置
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(pool.getConnectTimeout()))
                .build();
        connectionManagerBuilder.setDefaultConnectionConfig(connectionConfig);

        // 3. SSL 证书校验与内网自签名跳过
        if (ssl.isInsecure()) {
            try {
                SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                        .build();
                SSLConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(sslContext)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .build();
                connectionManagerBuilder.setSSLSocketFactory(sslSocketFactory);
                log.info("HTTP 客户端已启用 Insecure SSL 模式，信任所有自签名证书并跳过主机名校验");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("构建 Insecure SSLContext 失败", e);
            }
        }

        // 4. 构建 HttpClient 客户端并启用后台空闲与过期连接驱逐守护
        HttpClientBuilder clientBuilder = HttpClients.custom()
                .evictIdleConnections(TimeValue.of(pool.getIdleTimeout()))
                .evictExpiredConnections();

        // 5. 租借连接与读取超时控制 (RequestConfig)
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(pool.getConnectionRequestTimeout()))
                .setResponseTimeout(Timeout.of(pool.getReadTimeout()))
                .build();
        clientBuilder.setDefaultRequestConfig(requestConfig);

        // 6. 出口正向代理支持
        if (proxy.isEnabled()) {
            Assert.hasText(proxy.getHost(), "HTTP 代理已启用(ateng.boot.http.proxy.enabled=true)，代理主机 host 不能为空");
            HttpHost proxyHost = new HttpHost(proxy.getHost(), proxy.getPort());
            clientBuilder.setProxy(proxyHost);
            if (StringUtils.hasText(proxy.getUsername())) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(proxyHost),
                        new UsernamePasswordCredentials(proxy.getUsername(), proxy.getPassword() != null ? proxy.getPassword().toCharArray() : new char[0])
                );
                clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
            log.info("HTTP 客户端已启用出口代理: {}:{}", proxy.getHost(), proxy.getPort());
        }

        // 7. 回调业务自定义扩展 SPI
        if (customizers != null) {
            for (HttpClient5Customizer customizer : customizers) {
                customizer.customize(clientBuilder, connectionManagerBuilder);
            }
        }

        PoolingHttpClientConnectionManager connectionManager = connectionManagerBuilder.build();
        clientBuilder.setConnectionManager(connectionManager);

        CloseableHttpClient httpClient = clientBuilder.build();
        log.info("Apache HttpClient 5 生产级池化连接工厂初始化成功 [maxTotal={}, maxPerRoute={}, connectTimeout={}, readTimeout={}]",
                pool.getMaxConnections(), pool.getMaxPerRoute(), pool.getConnectTimeout(), pool.getReadTimeout());
        return new HttpComponentsClientHttpRequestFactory(httpClient) {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
                ClientHttpRequest request = super.createRequest(uri, httpMethod);
                Duration perRequestTimeout = HttpRequestContextHolder.getReadTimeout();
                if (perRequestTimeout != null) {
                    RequestOptionsApplier.applyTimeout(request, perRequestTimeout);
                }
                return request;
            }
        };
    }
}
