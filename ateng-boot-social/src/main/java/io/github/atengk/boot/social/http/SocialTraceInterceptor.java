package io.github.atengk.boot.social.http;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 出站社会化登录 HTTP 请求链路追踪拦截器
 * 负责提取当前线程 MDC traceId 并注入出站请求头 X-Trace-Id
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SocialTraceInterceptor implements ClientHttpRequestInterceptor {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get(MDC_TRACE_ID_KEY);
        if (StringUtils.hasText(traceId) && !request.getHeaders().containsKey(TRACE_HEADER)) {
            request.getHeaders().set(TRACE_HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
