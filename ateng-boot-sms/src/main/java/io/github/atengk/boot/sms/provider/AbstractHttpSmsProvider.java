package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.sms.model.SmsChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Spring 6 RestClient 的云厂商 HTTP 短信驱动抽象基类
 * 统一收敛网络客户端构建、实例复用缓存、单渠道无状态瞬态重试与 JSON 响应解析通用逻辑
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public abstract class AbstractHttpSmsProvider implements SmsProvider {

    protected final RestClient.Builder restClientBuilder;
    private final Map<Duration, RestClient> restClientCache = new ConcurrentHashMap<>();

    protected AbstractHttpSmsProvider(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
    }

    /**
     * 根据渠道指定的超时时间获取或构建复用 RestClient 实例
     *
     * @param timeout 超时时长
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(Duration timeout) {
        Duration key = timeout != null ? timeout : Duration.ofSeconds(10);
        return restClientCache.computeIfAbsent(key, this::createRestClient);
    }

    /**
     * 创建新的 RestClient 实例
     *
     * @param timeout 超时时长
     * @return 新建的 RestClient 实例
     */
    protected RestClient createRestClient(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (timeout != null) {
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
        }
        return restClientBuilder.clone()
                .requestFactory(factory)
                .build();
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
}
