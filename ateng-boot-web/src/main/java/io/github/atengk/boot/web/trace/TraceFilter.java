package io.github.atengk.boot.web.trace;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 请求全生命周期链路追踪过滤器
 * 提取或生成唯一链路追踪标识，注入日志 MDC 与响应头，并在请求结束时严格清理上下文
 *
 * @author Ateng
 * @since 2026-10-15
 */
public class TraceFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 默认 MDC 键名
     */
    public static final String MDC_TRACE_ID_KEY = "traceId";

    /**
     * 默认过滤器执行优先级：最高优先级确保最先进入并在出口最后清理
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final WebProperties webProperties;

    public TraceFilter(WebProperties webProperties) {
        this.webProperties = webProperties != null ? webProperties : new WebProperties();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String headerName = webProperties.getTrace().getHeader();
        if (headerName == null || headerName.isBlank()) {
            headerName = "X-Trace-Id";
        }

        // 1. 优先提取请求头中的追踪标识，缺失则生成高性能 32 位无横杠 UUID (无锁并发)
        String traceId = request.getHeader(headerName);
        if (traceId == null || traceId.isBlank()) {
            traceId = IdUtil.fastSimpleUUID();
        }

        // 2. 注入日志上下文 MDC 与 HTTP 响应头
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        response.setHeader(headerName, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 3. 严格清理当前线程的 MDC 追踪标识，防止线程池复用污染
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
