package io.github.atengk.boot.search.driver;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.spi.RestClientBuilderCustomizer;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.template.SearchCallback;
import io.github.atengk.boot.search.util.SearchExceptionClassifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
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
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
    private final SearchEngineType engineType;

    public ElasticsearchClientDriver(AtengSearchProperties properties) {
        this(properties, null, Collections.emptyList());
    }

    public ElasticsearchClientDriver(AtengSearchProperties properties, ObjectMapper customObjectMapper) {
        this(properties, customObjectMapper, Collections.emptyList());
    }

    public ElasticsearchClientDriver(AtengSearchProperties properties,
                                     ObjectMapper customObjectMapper,
                                     List<RestClientBuilderCustomizer> customizers) {
        Objects.requireNonNull(properties, "AtengSearchProperties must not be null");

        // 1. 初始化底层 RestClient
        this.restClient = buildRestClient(properties, customizers);

        // 2. 桥接 Jackson 序列化器（默认使用 Core 统一 long-to-string 契约并忽略 null 字段，防御局部更新覆写）
        ObjectMapper mapper = customObjectMapper != null ? customObjectMapper.copy() : JsonUtil.createLongToStringObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper(mapper);

        // 3. 构造 Transport 与 ElasticsearchClient
        this.transport = new RestClientTransport(this.restClient, jsonpMapper);
        this.elasticsearchClient = new ElasticsearchClient(this.transport);
        this.engineType = properties.getEngine() != null ? properties.getEngine() : SearchEngineType.ELASTICSEARCH;

        log.info("ElasticsearchClientDriver initialized successfully for nodes: {}", properties.getUris());
    }

    @Override
    public SearchEngineType getEngineType() {
        return this.engineType;
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
    public <R> R execute(SearchCallback<R> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        try {
            return callback.doInSearch(this.elasticsearchClient);
        } catch (SearchException se) {
            throw se;
        } catch (Exception e) {
            if (SearchExceptionClassifier.isVersionConflict(e)) {
                throw new SearchVersionConflictException("Version conflict during execute callback: " + e.getMessage(), e);
            }
            throw new SearchException(
                    SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                    "Failed to execute elasticsearch callback action: " + e.getMessage(), e);
        }
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

    private RestClient buildRestClient(AtengSearchProperties properties, List<RestClientBuilderCustomizer> customizers) {
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

        Integer connectTimeoutMs = toTimeoutMillisInt(properties.getConnectTimeout(), "connectTimeout");
        Integer socketTimeoutMs = toTimeoutMillisInt(properties.getSocketTimeout(), "socketTimeout");
        Integer connectionRequestTimeoutMs = toTimeoutMillisInt(properties.getConnectionRequestTimeout(), "connectionRequestTimeout");

        // 请求超时配置（包含连接池借用超时防无界挂起）
        builder.setRequestConfigCallback(requestConfigBuilder -> {
            if (connectTimeoutMs != null) {
                requestConfigBuilder.setConnectTimeout(connectTimeoutMs);
            }
            if (socketTimeoutMs != null) {
                requestConfigBuilder.setSocketTimeout(socketTimeoutMs);
            }
            if (connectionRequestTimeoutMs != null) {
                requestConfigBuilder.setConnectionRequestTimeout(connectionRequestTimeoutMs);
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

            // OpenSearch 兼容适配（标头协商）
            if (properties.getEngine() == SearchEngineType.OPENSEARCH) {
                // 1. 请求拦截器：替换 Content-Type / Accept 标头中的 Elastic 专有协议标头，防止 OpenSearch 抛出 406 Not Acceptable
                httpClientBuilder.addInterceptorLast((HttpRequestInterceptor) (request, context) -> {
                    if (request.containsHeader("Content-Type")) {
                        Header ct = request.getFirstHeader("Content-Type");
                        if (ct != null && ct.getValue() != null && ct.getValue().contains("application/vnd.elasticsearch+json")) {
                            request.setHeader("Content-Type", "application/json");
                        }
                    }
                    if (request.containsHeader("Accept")) {
                        Header accept = request.getFirstHeader("Accept");
                        if (accept != null && accept.getValue() != null && accept.getValue().contains("application/vnd.elasticsearch+json")) {
                            request.setHeader("Accept", "application/json");
                        }
                    }
                });

                // 2. 响应拦截器：补齐 X-Elastic-Product 标头以兼容 ElasticsearchClient 客户端产品校验
                httpClientBuilder.addInterceptorLast((HttpResponseInterceptor) (response, context) -> {
                    if (!response.containsHeader("X-Elastic-Product")) {
                        response.addHeader("X-Elastic-Product", "Elasticsearch");
                    }
                });
            }

            return httpClientBuilder;
        });

        if (customizers != null) {
            for (RestClientBuilderCustomizer customizer : customizers) {
                if (customizer != null) {
                    customizer.customize(builder, properties);
                }
            }
        }

        return builder.build();
    }

    private static final Duration MAX_TIMEOUT_DURATION = Duration.ofMillis(Integer.MAX_VALUE);

    private static Integer toTimeoutMillisInt(Duration duration, String propertyName) {
        if (duration == null) {
            return null;
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must not be negative");
        }
        if (duration.compareTo(MAX_TIMEOUT_DURATION) >= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) duration.toMillis();
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
                try (InputStream in = openTrustStoreStream(sslProperties.getTrustStorePath().trim())) {
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

    private static InputStream openTrustStoreStream(String trustStorePath) throws IOException {
        if (trustStorePath.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)
                || trustStorePath.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
            Resource resource = new DefaultResourceLoader().getResource(trustStorePath);
            return resource.getInputStream();
        }
        File trustStoreFile = new File(trustStorePath);
        return new FileInputStream(trustStoreFile);
    }
}
