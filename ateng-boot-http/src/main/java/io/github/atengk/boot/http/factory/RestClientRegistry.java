package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.interceptor.HttpLoggingInterceptor;
import io.github.atengk.boot.http.interceptor.HttpRetryInterceptor;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpSslProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 多命名 HTTP 客户端注册表与单例管理器
 * 统一纳管命名客户端 RestClient 实例缓存，共享底层物理连接池，提供多级 BaseUrl 解析、超时隔离与配置策略动态融合
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RestClientRegistry {

    private final RestClient.Builder baseRestClientBuilder;
    private final ClientHttpRequestFactory globalRequestFactory;
    private final HttpProperties properties;
    private final HttpErrorHandler httpErrorHandler;
    private final Environment environment;
    private final HttpLoggingInterceptor loggingInterceptor;

    private final ConcurrentMap<String, RestClient> clientCache = new ConcurrentHashMap<>();

    public RestClientRegistry(RestClient.Builder baseRestClientBuilder,
                              ClientHttpRequestFactory globalRequestFactory,
                              HttpProperties properties,
                              HttpErrorHandler httpErrorHandler,
                              Environment environment) {
        this(baseRestClientBuilder, globalRequestFactory, properties, httpErrorHandler, environment, null);
    }

    public RestClientRegistry(RestClient.Builder baseRestClientBuilder,
                              ClientHttpRequestFactory globalRequestFactory,
                              HttpProperties properties,
                              HttpErrorHandler httpErrorHandler,
                              Environment environment,
                              HttpLoggingInterceptor loggingInterceptor) {
        Assert.notNull(baseRestClientBuilder, "baseRestClientBuilder must not be null");
        Assert.notNull(globalRequestFactory, "globalRequestFactory must not be null");
        this.baseRestClientBuilder = baseRestClientBuilder;
        this.globalRequestFactory = globalRequestFactory;
        this.properties = properties != null ? properties : new HttpProperties();
        this.httpErrorHandler = httpErrorHandler;
        this.environment = environment;
        this.loggingInterceptor = loggingInterceptor;
    }

    /**
     * 获取或初始化命名客户端 RestClient 单例实例
     *
     * @param clientName    命名客户端唯一标识（对应 ateng.boot.http.clients.<name>）
     * @param annotationUrl 声明式注解上指定的 BaseUrl 原始路径（支持 ${...} 占位符，可为 null）
     * @return 专属于该命名客户端的 RestClient 单例实例
     */
    public RestClient getClient(String clientName, String annotationUrl) {
        Assert.hasText(clientName, "clientName must not be empty");
        return this.clientCache.computeIfAbsent(clientName, name -> buildClient(name, annotationUrl));
    }

    /**
     * 获取或初始化命名客户端 RestClient 单例实例
     *
     * @param clientName 命名客户端唯一标识
     * @return 专属于该命名客户端的 RestClient 单例实例
     */
    public RestClient getClient(String clientName) {
        return getClient(clientName, null);
    }

    /**
     * 显式注册自定义 RestClient 实例至缓存中
     *
     * @param clientName 客户端名称
     * @param restClient 自定义 RestClient 实例
     */
    public void register(String clientName, RestClient restClient) {
        Assert.hasText(clientName, "clientName must not be empty");
        Assert.notNull(restClient, "restClient must not be null");
        this.clientCache.put(clientName, restClient);
        log.info("Explicitly registered RestClient for clientName '{}'", clientName);
    }

    /**
     * 从缓存中移除指定的命名客户端单例
     *
     * @param clientName 客户端名称
     * @return true 表示移除成功，false 表示原本未注册
     */
    public boolean unregister(String clientName) {
        if (!StringUtils.hasText(clientName)) {
            return false;
        }
        return this.clientCache.remove(clientName) != null;
    }

    /**
     * 判断指定命名客户端是否已完成初始化注册
     *
     * @param clientName 客户端名称
     * @return true 已注册，false 未注册
     */
    public boolean contains(String clientName) {
        if (!StringUtils.hasText(clientName)) {
            return false;
        }
        return this.clientCache.containsKey(clientName);
    }

    /**
     * 获取当前已注册的命名客户端数量
     *
     * @return 客户端数量
     */
    public int size() {
        return this.clientCache.size();
    }

    /**
     * 清空所有命名客户端单例缓存
     */
    public void clear() {
        this.clientCache.clear();
        log.info("Cleared all cached RestClient instances in RestClientRegistry");
    }

    /**
     * 获取所有已注册的客户端名称只读集合
     *
     * @return 客户端名称不可变集合
     */
    public Set<String> getClientNames() {
        return Collections.unmodifiableSet(this.clientCache.keySet());
    }

    /**
     * 获取指定命名客户端的配置项，执行空安全防御
     *
     * @param clientName 客户端名称
     * @return 命名客户端配置项，未配置或不存在时返回 null
     */
    private HttpClientItemProperties getClientItemProperties(String clientName) {
        if (!StringUtils.hasText(clientName) || this.properties == null || this.properties.getClients() == null) {
            return null;
        }
        return this.properties.getClients().get(clientName);
    }

    /**
     * 解析命名客户端的最终目标 BaseUrl
     * 优先级策略：配置文件 ateng.boot.http.clients.<name>.base-url > 注解 url (支持占位符) > 全局兜底
     *
     * @param clientName    客户端名称
     * @param annotationUrl 注解原始 URL
     * @return 解析后的真实 BaseUrl，若未配置则返回空字符串
     */
    public String resolveBaseUrl(String clientName, String annotationUrl) {
        HttpClientItemProperties item = getClientItemProperties(clientName);

        // 1. 优先提取配置文件中的 base-url
        if (item != null && StringUtils.hasText(item.getBaseUrl())) {
            return resolvePlaceholders(item.getBaseUrl().trim());
        }

        // 2. 次之提取声明式注解上的 url
        if (StringUtils.hasText(annotationUrl)) {
            return resolvePlaceholders(annotationUrl.trim());
        }

        return "";
    }

    /**
     * 构建专属于该命名客户端的 RestClient 单例实例
     *
     * @param clientName    客户端名称
     * @param annotationUrl 注解 URL
     * @return 构建完成的 RestClient
     */
    private RestClient buildClient(String clientName, String annotationUrl) {
        // 1. 克隆全局基础 RestClient.Builder
        RestClient.Builder builder = this.baseRestClientBuilder.clone();

        // 2. 检索命名客户端专属配置项
        HttpClientItemProperties item = getClientItemProperties(clientName);

        // 3. 多级 BaseUrl 动态融合
        String resolvedBaseUrl = resolveBaseUrl(clientName, annotationUrl);
        if (StringUtils.hasText(resolvedBaseUrl)) {
            builder.baseUrl(resolvedBaseUrl);
        }

        // 4. 专属代理、SSL/mTLS 隔离或超时策略隔离与物理连接池共享
        ClientHttpRequestFactory clientFactory = resolveRequestFactory(item);
        builder.requestFactory(clientFactory);

        // 5. 注入客户端专属默认请求头（支持 ${...} 占位符解析）
        if (item != null && item.getDefaultHeaders() != null && !item.getDefaultHeaders().isEmpty()) {
            item.getDefaultHeaders().forEach((headerName, headerValue) -> {
                if (StringUtils.hasText(headerName) && headerValue != null) {
                    String resolvedValue = resolvePlaceholders(headerValue);
                    builder.defaultHeader(headerName, resolvedValue);
                }
            });
        }

        // 6. 四维上下文出站透传与非信任域隔离
        builder.defaultHeader(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, clientName);
        if (item != null) {
            builder.defaultHeader(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, String.valueOf(item.isPropagateContext()));
        }

        // 7. 专属重试策略隔离
        if (item != null && item.getRetry() != null) {
            HttpProperties scopedProperties = new HttpProperties();
            scopedProperties.setRetry(item.getRetry());
            HttpRetryInterceptor scopedRetryInterceptor = new HttpRetryInterceptor(scopedProperties, this.loggingInterceptor);
            builder.requestInterceptors(interceptors -> {
                boolean replaced = false;
                for (int i = 0; i < interceptors.size(); i++) {
                    ClientHttpRequestInterceptor existing = interceptors.get(i);
                    if (existing instanceof HttpRetryInterceptor) {
                        interceptors.set(i, scopedRetryInterceptor);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    interceptors.add(scopedRetryInterceptor);
                }
            });
        }

        // 8. 统一绑定 HTTP 状态码异常响应处理器
        if (this.httpErrorHandler != null) {
            builder.defaultStatusHandler(this.httpErrorHandler);
        }

        log.info("Initialized scoped RestClient for clientName '{}' [baseUrl={}, connectTimeout={}, readTimeout={}, propagateContext={}]",
                clientName, resolvedBaseUrl, item != null ? item.getConnectTimeout() : null, item != null ? item.getReadTimeout() : null, item != null ? item.isPropagateContext() : true);

        return builder.build();
    }

    /**
     * 为命名客户端解析底层 ClientHttpRequestFactory
     * 若客户端配置了专属 proxy 或专属 ssl (如 mTLS 客户端证书)，则构建专职物理连接工厂；否则默认共享全局连接池，按需应用超时隔离
     *
     * @param item 命名客户端配置项（可为 null）
     * @return 适用于该客户端的 ClientHttpRequestFactory
     */
    private ClientHttpRequestFactory resolveRequestFactory(HttpClientItemProperties item) {
        boolean hasCustomProxy = item != null && item.getProxy() != null;
        HttpSslProperties resolvedSsl = item != null ? item.getResolvedSsl() : null;
        boolean hasCustomSsl = resolvedSsl != null && (
                resolvedSsl.isInsecure() != this.properties.getSsl().isInsecure()
                || resolvedSsl.hasKeyStore()
        );

        ClientHttpRequestFactory baseFactory;
        if (hasCustomProxy || hasCustomSsl) {
            HttpProperties scopedProperties = new HttpProperties();
            scopedProperties.setEngine(this.properties.getEngine());
            scopedProperties.setPool(this.properties.getPool());
            scopedProperties.setProxy(hasCustomProxy ? item.getProxy() : this.properties.getProxy());
            scopedProperties.setSsl(hasCustomSsl ? resolvedSsl : this.properties.getSsl());
            baseFactory = ClientHttpRequestFactoryBuilder.build(scopedProperties, null);
        } else {
            baseFactory = this.globalRequestFactory;
        }

        Duration connectTimeout = item != null ? item.getConnectTimeout() : null;
        Duration readTimeout = item != null ? item.getReadTimeout() : null;
        if (connectTimeout != null || readTimeout != null) {
            return new ClientScopedHttpRequestFactory(baseFactory, readTimeout, connectTimeout);
        }
        return baseFactory;
    }

    /**
     * 解析字符串中的占位符
     *
     * @param text 待解析文本
     * @return 解析后的真实文本
     */
    private String resolvePlaceholders(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        if (this.environment != null) {
            return this.environment.resolvePlaceholders(text);
        }
        return text;
    }
}
