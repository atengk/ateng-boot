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
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
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

        // 3. SSL 证书校验、内网自签名跳过与金融级 mTLS 客户端双向认证
        boolean hasKeyStore = ssl.hasKeyStore();
        if (ssl.isInsecure() || hasKeyStore) {
            try {
                SSLContextBuilder sslContextBuilder = SSLContextBuilder.create();
                if (ssl.isInsecure()) {
                    sslContextBuilder.loadTrustMaterial(TrustAllStrategy.INSTANCE);
                }
                if (hasKeyStore) {
                    loadKeyMaterial(sslContextBuilder, ssl);
                }
                SSLConnectionSocketFactoryBuilder socketFactoryBuilder = SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(sslContextBuilder.build());
                if (ssl.isInsecure()) {
                    socketFactoryBuilder.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                    log.info("HTTP 客户端已启用 Insecure SSL 模式，信任所有自签名证书并跳过主机名校验");
                }
                connectionManagerBuilder.setSSLSocketFactory(socketFactoryBuilder.build());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("构建 SSLContext 失败: " + e.getMessage(), e);
            }
        }

        // 4. 构建 HttpClient 客户端并启用后台空闲与过期连接驱逐守护
        // 显式禁用底层隐性自动重试，重试统一由框架上层 HttpRetryInterceptor 与 Core RetryUtil 显式弹性纳管
        HttpClientBuilder clientBuilder = HttpClients.custom()
                .disableAutomaticRetries()
                .evictIdleConnections(TimeValue.of(pool.getIdleTimeout()))
                .evictExpiredConnections();

        // 5. 租借连接与读取超时控制 (RequestConfig)
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(pool.getConnectionRequestTimeout()))
                .setResponseTimeout(Timeout.of(pool.getReadTimeout()))
                .build();
        clientBuilder.setDefaultRequestConfig(requestConfig);

        // 6. 出口正向代理与白名单直连支持
        if (proxy.isEnabled()) {
            Assert.hasText(proxy.getHost(), "HTTP 代理已启用(ateng.boot.http.proxy.enabled=true)，代理主机 host 不能为空");
            HttpHost proxyHost = new HttpHost(proxy.getHost(), proxy.getPort());
            if (proxy.getNonProxyHosts() != null && !proxy.getNonProxyHosts().isEmpty()) {
                clientBuilder.setRoutePlanner(new DefaultProxyRoutePlanner(proxyHost) {
                    @Override
                    protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
                        if (target != null && proxy.isNonProxyHost(target.getHostName())) {
                            return null;
                        }
                        return super.determineProxy(target, context);
                    }
                });
            } else {
                clientBuilder.setProxy(proxyHost);
            }
            if (StringUtils.hasText(proxy.getUsername())) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(proxyHost),
                        new UsernamePasswordCredentials(proxy.getUsername(), proxy.getPassword() != null ? proxy.getPassword().toCharArray() : new char[0])
                );
                clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
            log.info("HTTP 客户端已启用出口代理: {}:{}, nonProxyHosts={}", proxy.getHost(), proxy.getPort(), proxy.getNonProxyHosts());
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
                Duration perRequestReadTimeout = HttpRequestContextHolder.getReadTimeout();
                Duration perRequestConnectTimeout = HttpRequestContextHolder.getConnectTimeout();
                if (perRequestReadTimeout != null || perRequestConnectTimeout != null) {
                    try {
                        RequestOptionsApplier.applyTimeout(request, perRequestReadTimeout, perRequestConnectTimeout);
                    } finally {
                        HttpRequestContextHolder.clear();
                    }
                }
                return request;
            }
        };
    }

    /**
     * 加载 mTLS 客户端私钥证书密钥库至 SSLContextBuilder
     *
     * @param sslContextBuilder SSL 上下文构建器
     * @param ssl               SSL 配置属性
     * @throws Exception 证书加载或解析异常
     */
    private static void loadKeyMaterial(SSLContextBuilder sslContextBuilder, HttpSslProperties ssl) throws Exception {
        String keyStorePath = ssl.getKeyStore();
        Assert.hasText(keyStorePath, "keyStore 路径不能为空");
        Resource resource = new DefaultResourceLoader().getResource(keyStorePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("未找到 mTLS 客户端证书文件: " + keyStorePath);
        }
        String type = StringUtils.hasText(ssl.getKeyStoreType()) ? ssl.getKeyStoreType() : "PKCS12";
        KeyStore keyStore = KeyStore.getInstance(type);
        char[] password = StringUtils.hasText(ssl.getKeyStorePassword()) ? ssl.getKeyStorePassword().toCharArray() : new char[0];
        try (InputStream in = resource.getInputStream()) {
            keyStore.load(in, password);
        }
        sslContextBuilder.loadKeyMaterial(keyStore, password);
        log.info("成功加载 mTLS 客户端私钥证书: [{}] (type: {})", keyStorePath, type);
    }
}
