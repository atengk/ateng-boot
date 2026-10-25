package io.github.atengk.boot.search.driver;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Elasticsearch 官方客户端驱动实现
 * <p>
 * 封装 RestClient 连接池、TLS/SSL、身份认证、MDC 链路透传及 Jackson 序列化映射。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Slf4j
public class ElasticsearchClientDriver implements SearchEngineClient {

    private final RestClient restClient;
    private final RestClientTransport transport;
    private final ElasticsearchClient elasticsearchClient;

    public ElasticsearchClientDriver(AtengSearchProperties properties) {
        this(properties, null);
    }

    public ElasticsearchClientDriver(AtengSearchProperties properties, ObjectMapper customObjectMapper) {
        Objects.requireNonNull(properties, "AtengSearchProperties must not be null");

        // 1. 初始化底层 RestClient
        this.restClient = buildRestClient(properties);

        // 2. 桥接 Jackson 序列化器（默认使用 Core 统一 long-to-string 契约）
        ObjectMapper mapper = customObjectMapper != null ? customObjectMapper : JsonUtil.createLongToStringObjectMapper();
        JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper(mapper);

        // 3. 构造 Transport 与 ElasticsearchClient
        this.transport = new RestClientTransport(this.restClient, jsonpMapper);
        this.elasticsearchClient = new ElasticsearchClient(this.transport);

        log.info("ElasticsearchClientDriver initialized successfully for nodes: {}", properties.getUris());
    }

    @Override
    public SearchEngineType getEngineType() {
        return SearchEngineType.ELASTICSEARCH;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getNativeClient(Class<T> clientClass) {
        Objects.requireNonNull(clientClass, "clientClass must not be null");
        if (clientClass.isInstance(this.elasticsearchClient)) {
            return (T) this.elasticsearchClient;
        }
        if (clientClass.isInstance(this.restClient)) {
            return (T) this.restClient;
        }
        if (clientClass.isInstance(this.transport)) {
            return (T) this.transport;
        }
        throw new IllegalArgumentException("Unsupported native client class: " + clientClass.getName()
                + ". Available classes: ElasticsearchClient, RestClient, RestClientTransport");
    }

    @Override
    public ElasticsearchClient getElasticsearchClient() {
        return this.elasticsearchClient;
    }

    @Override
    public void close() throws IOException {
        log.info("Closing ElasticsearchClientDriver resources...");
        try {
            if (this.transport != null) {
                this.transport.close();
            }
        } finally {
            if (this.restClient != null) {
                this.restClient.close();
            }
        }
    }

    private RestClient buildRestClient(AtengSearchProperties properties) {
        List<String> uris = properties.getUris();
        if (uris == null || uris.isEmpty()) {
            throw new SearchException(SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR, "Elasticsearch URIs must not be empty");
        }

        HttpHost[] httpHosts = uris.stream()
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);

        RestClientBuilder builder = RestClient.builder(httpHosts);

        // 子路径前缀支持
        if (StringUtils.hasText(properties.getPathPrefix())) {
            builder.setPathPrefix(properties.getPathPrefix());
        }

        // 默认 Header 集合（如 API Key）
        List<Header> defaultHeaders = new ArrayList<>();
        if (StringUtils.hasText(properties.getApiKey())) {
            defaultHeaders.add(new BasicHeader("Authorization", "ApiKey " + properties.getApiKey()));
        }
        if (!defaultHeaders.isEmpty()) {
            builder.setDefaultHeaders(defaultHeaders.toArray(new Header[0]));
        }

        // 请求超时配置
        builder.setRequestConfigCallback(requestConfigBuilder -> {
            if (properties.getConnectTimeout() != null) {
                requestConfigBuilder.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            }
            if (properties.getSocketTimeout() != null) {
                requestConfigBuilder.setSocketTimeout((int) properties.getSocketTimeout().toMillis());
            }
            return requestConfigBuilder;
        });

        // 客户端连接池与 SSL/认证/MDC 配置
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder.setMaxConnTotal(properties.getMaxConnections());
            httpClientBuilder.setMaxConnPerRoute(properties.getMaxConnectionsPerRoute());

            if (properties.getKeepAlive() != null && !properties.getKeepAlive().isNegative()) {
                httpClientBuilder.setKeepAliveStrategy((response, context) -> properties.getKeepAlive().toMillis());
            }

            // 用户名密码认证（当未配置 ApiKey 时）
            if (!StringUtils.hasText(properties.getApiKey())
                    && StringUtils.hasText(properties.getUsername())
                    && StringUtils.hasText(properties.getPassword())) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        AuthScope.ANY,
                        new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword())
                );
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }

            // SSL/TLS 配置
            configureSsl(httpClientBuilder, properties.getSsl());

            // 链路追踪 MDC 透传拦截器
            httpClientBuilder.addInterceptorLast((HttpRequestInterceptor) (request, context) -> {
                String traceId = MDC.get("traceId");
                if (StringUtils.hasText(traceId)) {
                    request.addHeader("X-Trace-Id", traceId);
                }
            });

            return httpClientBuilder;
        });

        return builder.build();
    }

    private void configureSsl(org.apache.http.impl.nio.client.HttpAsyncClientBuilder httpClientBuilder,
                              AtengSearchProperties.SslProperties sslProperties) {
        if (sslProperties == null || !sslProperties.isEnabled()) {
            return;
        }

        try {
            SSLContextBuilder sslContextBuilder = SSLContextBuilder.create();

            if (sslProperties.isInsecureSkipVerify()) {
                sslContextBuilder.loadTrustMaterial(null, TrustAllStrategy.INSTANCE);
                httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } else if (StringUtils.hasText(sslProperties.getTrustStorePath())) {
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                char[] password = StringUtils.hasText(sslProperties.getTrustStorePassword())
                        ? sslProperties.getTrustStorePassword().toCharArray()
                        : null;
                File trustStoreFile = new File(sslProperties.getTrustStorePath());
                try (InputStream in = new FileInputStream(trustStoreFile)) {
                    trustStore.load(in, password);
                }
                sslContextBuilder.loadTrustMaterial(trustStore, null);
            }

            SSLContext sslContext = sslContextBuilder.build();
            httpClientBuilder.setSSLContext(sslContext);
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR,
                    "Failed to configure SSL for Elasticsearch client: " + e.getMessage(), e);
        }
    }
}
