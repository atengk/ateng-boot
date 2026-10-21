package io.github.atengk.boot.http.options;

import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
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
            HttpRequestContextHolder.setReadTimeout(options.getReadTimeout());
            spec.httpRequest(clientRequest -> applyTimeout(clientRequest, options.getReadTimeout()));
        }

        // 3. 注入单次请求上下文透传显式控制与客户端标识（供拦截器链路消费并在出站前严格剥离）
        if (options.getPropagateContext() != null) {
            spec.header(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, String.valueOf(options.getPropagateContext()));
        }
        if (options.getClientName() != null && !options.getClientName().isBlank()) {
            spec.header(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, options.getClientName());
        }
    }

    /**
     * 自适应将单次读取超时应用至底层物理请求
     *
     * @param clientRequest 底层 ClientHttpRequest 实例
     * @param timeout       单次读取超时时间
     */
    public static void applyTimeout(ClientHttpRequest clientRequest, Duration timeout) {
        applyTimeout(clientRequest, timeout, null);
    }

    /**
     * 自适应将读取超时与建联超时应用至底层物理请求
     *
     * @param clientRequest  底层 ClientHttpRequest 实例
     * @param readTimeout    读取超时时间
     * @param connectTimeout 建联超时时间
     */
    public static void applyTimeout(ClientHttpRequest clientRequest, Duration readTimeout, Duration connectTimeout) {
        if (clientRequest == null || (readTimeout == null && connectTimeout == null)) {
            return;
        }

        ClientHttpRequest current = clientRequest;
        while (current != null) {
            // 尝试 HttpClient 5 适配
            if (HTTPCLIENT5_PRESENT && tryApplyHttpClient5(current, readTimeout, connectTimeout)) {
                return;
            }

            // 尝试 JDK 17 原生 Client 适配（JDK 17 原生 Client 单次请求仅支持设置读取超时）
            if (tryApplyJdkClient(current, readTimeout)) {
                return;
            }

            // 递归解包装饰器 (如 InterceptingClientHttpRequest 或 BufferingClientHttpRequestWrapper)
            current = unwrapDelegate(current);
        }
    }

    private static boolean tryApplyHttpClient5(ClientHttpRequest request, Duration readTimeout, Duration connectTimeout) {
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
                    if (readTimeout != null) {
                        builder.setResponseTimeout(org.apache.hc.core5.util.Timeout.of(readTimeout));
                    }
                    if (connectTimeout != null) {
                        builder.setConnectTimeout(org.apache.hc.core5.util.Timeout.of(connectTimeout));
                    }
                    uriRequest.setConfig(builder.build());
                }
            }

            Field httpContextField = ReflectionUtils.findField(request.getClass(), "httpContext");
            if (httpContextField != null) {
                ReflectionUtils.makeAccessible(httpContextField);
                Object context = httpContextField.get(request);
                if (context instanceof org.apache.hc.core5.http.protocol.HttpContext httpContext) {
                    org.apache.hc.client5.http.config.RequestConfig.Builder ctxBuilder = org.apache.hc.client5.http.config.RequestConfig.custom();
                    if (readTimeout != null) {
                        ctxBuilder.setResponseTimeout(org.apache.hc.core5.util.Timeout.of(readTimeout));
                    }
                    if (connectTimeout != null) {
                        ctxBuilder.setConnectTimeout(org.apache.hc.core5.util.Timeout.of(connectTimeout));
                    }
                    org.apache.hc.client5.http.protocol.HttpClientContext.adapt(httpContext).setRequestConfig(ctxBuilder.build());
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
        } catch (Exception ex) {
            log.debug("反射解包底层 ClientHttpRequest 委托对象失败，终止委托链探查: {}", ex.getMessage());
        }
        return null;
    }
}
