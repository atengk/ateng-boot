package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.properties.SmsProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 Spring 6 RestClient 的云厂商 HTTP 短信驱动抽象基类
 * 统一收敛有界 LRU 网络客户端构建与代理配置、端点协议头防御补全、无状态瞬态重试与 JSON 响应解析通用逻辑
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public abstract class AbstractHttpSmsProvider implements SmsProvider {

    /**
     * 默认 RestClient 缓存最大条目容量（防动态代理与超时组合引发内存膨胀）
     */
    public static final int DEFAULT_MAX_CLIENT_CACHE_SIZE = 256;

    protected final RestClient.Builder restClientBuilder;
    private final ConcurrentLruCache<ClientCacheKey, RestClient> restClientCache;

    protected AbstractHttpSmsProvider(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DEFAULT_MAX_CLIENT_CACHE_SIZE);
    }

    protected AbstractHttpSmsProvider(RestClient.Builder restClientBuilder, int maxClientCacheSize) {
        this.restClientBuilder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        this.restClientCache = new ConcurrentLruCache<>(maxClientCacheSize > 0 ? maxClientCacheSize : DEFAULT_MAX_CLIENT_CACHE_SIZE);
    }

    /**
     * 根据渠道配置（含超时时长与独立 HTTP 代理）获取或构建复用 RestClient 实例
     *
     * @param channel 目标短信渠道
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(SmsChannel channel) {
        Duration timeout = channel != null ? channel.getTimeout() : null;
        SmsProxyProperties proxy = channel != null ? channel.getProxy() : null;
        return buildRestClient(timeout, proxy);
    }

    /**
     * 根据渠道指定的超时时间获取或构建复用 RestClient 实例
     *
     * @param timeout 超时时长
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(Duration timeout) {
        return buildRestClient(timeout, null);
    }

    /**
     * 根据超时时间与代理配置从有界 LRU 缓存获取或创建 RestClient 实例
     *
     * @param timeout 超时时长
     * @param proxy   代理配置（可为 null）
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(Duration timeout, SmsProxyProperties proxy) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(10);
        ClientCacheKey cacheKey = ClientCacheKey.of(effectiveTimeout, proxy);
        return restClientCache.computeIfAbsent(cacheKey, key -> createRestClient(effectiveTimeout, proxy));
    }

    /**
     * 创建新的 RestClient 实例（无代理）
     *
     * @param timeout 超时时长
     * @return 新建的 RestClient 实例
     */
    protected RestClient createRestClient(Duration timeout) {
        return createRestClient(timeout, null);
    }

    /**
     * 创建新的 RestClient 实例（支持可选 HTTP 正向代理与 Basic 认证）
     *
     * @param timeout 超时时长
     * @param proxy   代理配置（可为 null）
     * @return 新建的 RestClient 实例
     */
    protected RestClient createRestClient(Duration timeout, SmsProxyProperties proxy) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (timeout != null) {
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
        }

        RestClient.Builder builder = restClientBuilder.clone().requestFactory(factory);
        if (proxy != null && proxy.isEnabled()) {
            BusinessAssert.hasText(proxy.getHost(), "启用渠道 HTTP 代理时 host 不能为空");
            int port = proxy.getPort();
            BusinessAssert.isTrue(port > 0 && port <= 65535,
                    () -> "启用渠道 HTTP 代理时 port 必须在 1~65535 之间，当前值: " + port);

            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost().trim(), port)));

            if (StringUtils.hasText(proxy.getUsername())) {
                String credentials = proxy.getUsername().trim() + ":" + (proxy.getPassword() != null ? proxy.getPassword() : "");
                String basicToken = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                builder.requestInterceptor((req, body, execution) -> {
                    if (!req.getHeaders().containsKey("Proxy-Authorization")) {
                        req.getHeaders().set("Proxy-Authorization", basicToken);
                    }
                    return execution.execute(req, body);
                });
            }
        }
        return builder.build();
    }

    /**
     * 对配置端点执行协议头防御性补全与格式校验
     * 缺省配置返回默认公网端点；未显式声明 {@code http://} 或 {@code https://} 时自动补全 {@code https://}
     *
     * @param endpoint        渠道配置的自定义端点
     * @param defaultEndpoint 厂商官方默认公网端点
     * @return 规范化后的绝对 HTTP/HTTPS 端点 URL
     */
    protected String normalizeEndpoint(String endpoint, String defaultEndpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return defaultEndpoint;
        }
        String clean = endpoint.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            clean = "https://" + clean;
        }
        URI parsed = URI.create(clean);
        BusinessAssert.hasText(parsed.getHost(), () -> "短信网关接入端点格式非法，缺少有效主机名: " + endpoint);
        return clean;
    }

    /**
     * 获取当前已缓存的 RestClient 实例数量（供单测与监控探测）
     *
     * @return 缓存实例数量
     */
    public int getCachedRestClientCount() {
        return restClientCache.size();
    }

    /**
     * 单渠道可重试任务执行回调
     *
     * @param <T> 返回值泛型
     */
    @FunctionalInterface
    public interface SmsExecutionCallback<T> {
        T execute() throws Exception;
    }

    /**
     * 线程安全无状态单渠道重试辅助执行器
     * 在当前方法调用栈上维护重试计数，严禁使用实例变量存储重试状态
     *
     * @param channel    当前发信渠道
     * @param actionName 操作名称
     * @param callback   执行回调
     * @param <T>        返回值泛型
     * @return 回调执行结果
     * @throws Exception 当重试耗尽或遭遇不可重试异常时抛出
     */
    protected <T> T executeWithRetry(SmsChannel channel, String actionName, SmsExecutionCallback<T> callback) throws Exception {
        int maxRetries = channel != null ? Math.max(0, channel.getMaxRetries()) : 0;
        Duration retryInterval = (channel != null && channel.getRetryInterval() != null)
                ? channel.getRetryInterval()
                : Duration.ofSeconds(1);

        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(maxRetries + 1)
                .delay(retryInterval)
                .multiplier(1.0)
                .maxDelay(retryInterval)
                .retryPredicate(th -> {
                    Throwable target = (th instanceof RuntimeException && th.getCause() instanceof Exception) ? th.getCause() : th;
                    return target instanceof Exception ex && isRetryableException(ex);
                })
                .onRetry((attempt, error) -> {
                    Throwable target = (error instanceof RuntimeException && error.getCause() instanceof Exception) ? error.getCause() : error;
                    if (log.isWarnEnabled()) {
                        log.warn("[AbstractHttpSmsProvider] 渠道 [{}] 执行 [{}] 遭遇瞬态异常 ({})，准备进行第 {}/{} 次重试 (等待: {}ms)",
                                channel != null ? channel.getName() : "unknown",
                                actionName,
                                target.getMessage(),
                                attempt,
                                maxRetries,
                                retryInterval.toMillis());
                    }
                })
                .build();

        try {
            return RetryUtil.execute(() -> {
                try {
                    return callback.execute();
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }, retryConfig);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof Exception ex && !(re instanceof RestClientResponseException)) {
                throw ex;
            }
            throw re;
        }
    }

    /**
     * 判断异常是否为可重试的瞬态网络或服务端 5xx 故障
     *
     * @param ex 捕获的异常
     * @return true 表示可重试
     */
    protected boolean isRetryableException(Exception ex) {
        if (ex instanceof InterruptedException) {
            return false;
        }
        if (ex instanceof RestClientResponseException rre) {
            return rre.getStatusCode().is5xxServerError();
        }
        return ex instanceof IOException
                || ex instanceof ResourceAccessException
                || (ex.getCause() instanceof IOException);
    }

    /**
     * 安全解析 JSON 字符串为 Map 对象，解析异常时保底返回空 Map
     *
     * @param json JSON 字符串
     * @return 键值对 Map
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJsonSafely(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtil.parseObject(json, Map.class);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private record ClientCacheKey(Duration timeout,
                                  boolean proxyEnabled,
                                  String proxyHost,
                                  int proxyPort,
                                  String proxyUsername,
                                  String proxyPassword) {

        static ClientCacheKey of(Duration timeout, SmsProxyProperties proxy) {
            if (proxy == null || !proxy.isEnabled()) {
                return new ClientCacheKey(timeout, false, null, 0, null, null);
            }
            return new ClientCacheKey(
                    timeout,
                    true,
                    proxy.getHost() != null ? proxy.getHost().trim() : null,
                    proxy.getPort(),
                    proxy.getUsername() != null ? proxy.getUsername().trim() : null,
                    proxy.getPassword()
            );
        }
    }
}
