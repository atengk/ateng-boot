package io.github.atengk.boot.http.options;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;

/**
 * RequestOptions 参数应用与 URI 安全格式化工具
 * 负责依据 RFC 3986 标准对 Query 参数进行编码转译，并自适应应用请求头及单次超时覆盖
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public final class RequestOptionsApplier {

    private static final boolean HTTPCLIENT5_PRESENT = ClassUtils.isPresent(
            "org.apache.hc.client5.http.classic.methods.HttpUriRequestBase",
            RequestOptionsApplier.class.getClassLoader()
    );

    private RequestOptionsApplier() {
    }

    /**
     * 解析并生成符合 RFC 3986 标准安全编码的请求 URI
     *
     * @param url     基础请求路径
     * @param options 单次请求配置载荷（可为 null）
     * @return 经编码转译与变量替换后的 URI 对象
     */
    public static URI resolveUri(String url, RequestOptions options) {
        Assert.hasText(url, "请求 URL 不能为空");
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (options != null) {
            if (!CollectionUtils.isEmpty(options.getQueryParams())) {
                builder.queryParams(options.getQueryParams());
            }
            if (!CollectionUtils.isEmpty(options.getUriVariables())) {
                return builder.buildAndExpand(options.getUriVariables()).encode().toUri();
            }
        }
        return builder.build().encode().toUri();
    }

    /**
     * 将 RequestOptions 中的 Header 与单次超时覆盖应用至 RestClient 请求规范
     *
     * @param spec    RestClient 请求规范构建器
     * @param options 单次请求配置载荷
     */
    public static void apply(RestClient.RequestHeadersSpec<?> spec, RequestOptions options) {
        Assert.notNull(spec, "spec 实例不可为 null");
        if (options == null) {
            return;
        }

        // 1. 注入请求头
        if (!options.getHeaders().isEmpty()) {
            spec.headers(h -> h.addAll(options.getHeaders()));
        }

        // 2. 覆盖单次读取超时
        if (options.getReadTimeout() != null) {
            spec.httpRequest(clientRequest -> applyTimeout(clientRequest, options.getReadTimeout()));
        }
    }

    /**
     * 自适应将单次读取超时应用至底层物理请求
     *
     * @param clientRequest 底层 ClientHttpRequest 实例
     * @param timeout       单次读取超时时间
     */
    public static void applyTimeout(ClientHttpRequest clientRequest, Duration timeout) {
        if (clientRequest == null || timeout == null) {
            return;
        }

        ClientHttpRequest current = clientRequest;
        while (current != null) {
            // 尝试 HttpClient 5 适配
            if (HTTPCLIENT5_PRESENT && tryApplyHttpClient5(current, timeout)) {
                return;
            }

            // 尝试 JDK 17 原生 Client 适配
            if (tryApplyJdkClient(current, timeout)) {
                return;
            }

            // 递归解包装饰器 (如 InterceptingClientHttpRequest 或 BufferingClientHttpRequestWrapper)
            current = unwrapDelegate(current);
        }
    }

    private static boolean tryApplyHttpClient5(ClientHttpRequest request, Duration timeout) {
        if (!request.getClass().getName().contains("HttpComponentsClientHttpRequest")) {
            return false;
        }
        try {
            Field httpRequestField = ReflectionUtils.findField(request.getClass(), "httpRequest");
            if (httpRequestField != null) {
                ReflectionUtils.makeAccessible(httpRequestField);
                Object classicRequest = httpRequestField.get(request);
                if (classicRequest instanceof org.apache.hc.client5.http.classic.methods.HttpUriRequestBase uriRequest) {
                    org.apache.hc.client5.http.config.RequestConfig currentConfig = uriRequest.getConfig();
                    org.apache.hc.client5.http.config.RequestConfig.Builder builder = currentConfig != null
                            ? org.apache.hc.client5.http.config.RequestConfig.copy(currentConfig)
                            : org.apache.hc.client5.http.config.RequestConfig.custom();
                    builder.setResponseTimeout(org.apache.hc.core5.util.Timeout.of(timeout));
                    uriRequest.setConfig(builder.build());
                }
            }

            Field httpContextField = ReflectionUtils.findField(request.getClass(), "httpContext");
            if (httpContextField != null) {
                ReflectionUtils.makeAccessible(httpContextField);
                Object context = httpContextField.get(request);
                if (context instanceof org.apache.hc.core5.http.protocol.HttpContext httpContext) {
                    org.apache.hc.client5.http.protocol.HttpClientContext.adapt(httpContext).setRequestConfig(
                            org.apache.hc.client5.http.config.RequestConfig.custom()
                                    .setResponseTimeout(org.apache.hc.core5.util.Timeout.of(timeout))
                                    .build()
                    );
                }
            }
            return true;
        } catch (Exception ex) {
            log.warn("应用 Apache HttpClient 5 单次超时覆盖失败: {}", NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
            return false;
        }
    }

    private static boolean tryApplyJdkClient(ClientHttpRequest request, Duration timeout) {
        if (!request.getClass().getName().contains("JdkClientHttpRequest")) {
            return false;
        }
        try {
            Field timeoutField = ReflectionUtils.findField(request.getClass(), "timeout");
            if (timeoutField != null) {
                ReflectionUtils.makeAccessible(timeoutField);
                timeoutField.set(request, timeout);
                return true;
            }
        } catch (Exception ex) {
            log.warn("应用 JDK 17 原生 Client 单次超时覆盖失败: {}", NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        }
        return false;
    }

    private static ClientHttpRequest unwrapDelegate(ClientHttpRequest request) {
        try {
            Field delegateField = ReflectionUtils.findField(request.getClass(), "delegate");
            if (delegateField == null) {
                delegateField = ReflectionUtils.findField(request.getClass(), "request");
            }
            if (delegateField != null) {
                ReflectionUtils.makeAccessible(delegateField);
                Object next = delegateField.get(request);
                if (next instanceof ClientHttpRequest nextReq && nextReq != request) {
                    return nextReq;
                }
            }
        } catch (Exception ignored) {
            // 降级退出
        }
        return null;
    }
}
