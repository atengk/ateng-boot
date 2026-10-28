package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpRetryProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 出站 HTTP 弹性指数退避重试拦截器
 * 拦截链执行时序置于日志拦截器外层（Trace -> Retry -> Logging -> 物理传输），
 * 深度复用 ateng-boot-core 的 RetryUtil 函数式引擎，针对幂等动词在遭遇网络 I/O 抖动与特定服务端状态码时执行退避重试与随机抖动
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class HttpRetryInterceptor implements ClientHttpRequestInterceptor {

    private final HttpProperties properties;
    private final HttpLoggingInterceptor loggingInterceptor;

    public HttpRetryInterceptor(HttpProperties properties) {
        this(properties, null);
    }

    public HttpRetryInterceptor(HttpProperties properties, HttpLoggingInterceptor loggingInterceptor) {
        this.properties = properties != null ? properties : new HttpProperties();
        this.loggingInterceptor = loggingInterceptor;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpRetryProperties retry = resolveRetryProperties(request.getURI());
        if (retry == null || !retry.isEnabled()) {
            return executeSingleAttempt(request, body, execution);
        }

        // 1. 严格检查 HTTP 动词白名单（默认仅针对幂等动词 GET、HEAD，坚决杜绝盲目重试非幂等操作）
        if (!isRetryableMethod(request.getMethod(), retry.getRetryMethods())) {
            return executeSingleAttempt(request, body, execution);
        }

        // 2. 基于 Core 模块原生 RetryUtil 构建强类型重试配置模型
        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(retry.getMaxAttempts())
                .delay(retry.getDelay())
                .multiplier(retry.getMultiplier())
                .maxDelay(retry.getMaxDelay())
                .jitter(retry.isJitter())
                .retryPredicate(throwable -> {
                    if (throwable instanceof RetryableStatusCodeException ex) {
                        return retry.getRetryStatusCodes().contains(ex.getStatusCode());
                    }
                    return throwable instanceof RetryableIoException;
                })
                .onRetry((attempt, ex) -> {
                    // 当遭遇重试状态码（502/503/504）触发重试时，显式关闭上一次失败轮次的物理响应流，安全归还连接池，严防物理连接泄漏
                    if (ex instanceof RetryableStatusCodeException rse && rse.getResponse() != null) {
                        try {
                            rse.getResponse().close();
                        } catch (Exception closeEx) {
                            log.debug("释放重试中间失败响应流异常: {}", closeEx.getMessage());
                        }
                    }
                    String reason = ex instanceof RetryableStatusCodeException rse
                            ? "HTTP 状态码 " + rse.getStatusCode()
                            : ex.getMessage();
                    log.warn("HTTP {} {} 第 {} 次调用遭遇临时故障 [{}], 正在执行指数退避重试...",
                            request.getMethod(), request.getURI(), attempt, reason);
                })
                .build();

        // 3. 调度函数式指数退避重试执行器
        try {
            return RetryUtil.execute(() -> {
                try {
                    ClientHttpResponse response = executeSingleAttempt(request, body, execution);
                    int statusCode = response.getStatusCode().value();
                    if (retry.getRetryStatusCodes().contains(statusCode)) {
                        throw new RetryableStatusCodeException(response, statusCode);
                    }
                    return response;
                } catch (IOException e) {
                    throw new RetryableIoException(e);
                }
            }, retryConfig);
        } catch (RetryableStatusCodeException ex) {
            // 重试耗尽后，返回最后一次失败的响应对象，交由下游 HttpErrorHandler 转译为语义化 HttpRemoteException
            return ex.getResponse();
        } catch (RetryableIoException ex) {
            // 重试耗尽后，重新抛出原始物理根因 IOException，保持因果链
            throw ex.getCause();
        }
    }

    /**
     * 调度单次物理尝试，若挂载了内层日志拦截器则环绕执行以确保每次重试尝试均被独立记录
     */
    protected ClientHttpResponse executeSingleAttempt(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (this.loggingInterceptor != null) {
            return this.loggingInterceptor.intercept(request, body, execution);
        }
        return execution.execute(request, body);
    }

    /**
     * 判定当前 HTTP 动词是否属于配置允许的重试白名单
     */
    protected boolean isRetryableMethod(HttpMethod method, List<String> allowedMethods) {
        if (method == null || allowedMethods == null || allowedMethods.isEmpty()) {
            return false;
        }
        String methodName = method.name();
        return allowedMethods.stream().anyMatch(m -> m.equalsIgnoreCase(methodName));
    }

    /**
     * 判断当前重试拦截器是否已内聚托管执行出站日志拦截器
     *
     * @return true 表示已托管，无需再作为独立拦截器重复注入 RestClient
     */
    public boolean isLoggingDelegated() {
        return this.loggingInterceptor != null;
    }

    /**
     * 依据目标 URL 最长前缀自适应匹配客户端配置专属重试策略，未匹配回退全局重试配置
     */
    protected HttpRetryProperties resolveRetryProperties(URI uri) {
        Map<String, HttpClientItemProperties> clients = properties.getClients();
        Map.Entry<String, HttpClientItemProperties> matched = HttpInterceptorUtil.matchClientByUrl(uri, clients);
        if (matched != null && matched.getValue() != null && matched.getValue().getRetry() != null) {
            return matched.getValue().getRetry();
        }
        return properties.getRetry();
    }

    /**
     * 内部用于触发 RetryUtil 退避的远端异常状态码载荷包装类
     */
    @Getter
    private static class RetryableStatusCodeException extends RuntimeException {
        private final ClientHttpResponse response;
        private final int statusCode;

        public RetryableStatusCodeException(ClientHttpResponse response, int statusCode) {
            super("HTTP 远端服务响应可重试状态码: " + statusCode);
            this.response = response;
            this.statusCode = statusCode;
        }
    }

    /**
     * 内部用于触发 RetryUtil 退避的底层网络 I/O 异常包装类
     */
    private static class RetryableIoException extends RuntimeException {
        private final IOException cause;

        public RetryableIoException(IOException cause) {
            super(cause.getMessage(), cause);
            this.cause = cause;
        }

        @Override
        public synchronized IOException getCause() {
            return this.cause;
        }
    }
}
