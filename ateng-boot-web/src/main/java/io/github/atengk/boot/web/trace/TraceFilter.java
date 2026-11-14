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
import java.util.concurrent.atomic.AtomicLong;

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
     * 默认链路追踪响应头键名
     */
    public static final String DEFAULT_HEADER = "X-Trace-Id";

    /**
     * 默认过滤器执行优先级：最高优先级确保最先进入并在出口最后清理
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    /**
     * 默认入站 TraceId 最大允许长度
     */
    private static final int DEFAULT_MAX_TRACE_ID_LENGTH = 64;

    /**
     * Actuator 优雅停机控制面默认路径
     */
    private static final String ACTUATOR_PRE_STOP_PATH = "/actuator/pre-stop";

    /**
     * Spring MVC 优雅停机控制面默认路径
     */
    private static final String DEFAULT_MVC_PRE_STOP_PATH = "/pre-stop";

    private final WebProperties webProperties;

    /**
     * 当前在途 HTTP 请求计数器
     */
    private final AtomicLong activeRequests = new AtomicLong(0L);

    public TraceFilter(WebProperties webProperties) {
        this.webProperties = webProperties != null ? webProperties : new WebProperties();
    }

    /**
     * 获取当前仍在处理中的在途 HTTP 请求数
     *
     * @return 当前活跃请求数量
     */
    public long getActiveRequestCount() {
        return activeRequests.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        WebProperties.TraceProperties traceProps = webProperties != null ? webProperties.getTrace() : null;
        String headerName = traceProps != null ? traceProps.getHeader() : null;
        if (headerName == null || headerName.isBlank()) {
            headerName = DEFAULT_HEADER;
        }

        // 1. 提取并严格校验入站追踪标识，防御 CRLF 日志注入与超长载荷；不合法时生成安全 32 位 UUID
        int configuredMax = traceProps != null ? traceProps.getMaxLength() : DEFAULT_MAX_TRACE_ID_LENGTH;
        int maxLength = configuredMax > 0 ? configuredMax : DEFAULT_MAX_TRACE_ID_LENGTH;
        String inboundTraceId = request.getHeader(headerName);
        String traceId = isValidTraceId(inboundTraceId, maxLength)
                ? inboundTraceId
                : IdUtil.fastSimpleUUID();

        // 2. 注入日志上下文 MDC 与 HTTP 响应头
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        response.setHeader(headerName, traceId);

        boolean trackInFlight = !isShutdownControlPlaneRequest(request);
        if (trackInFlight) {
            activeRequests.incrementAndGet();
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (trackInFlight) {
                activeRequests.decrementAndGet();
            }
            // 3. 严格清理当前线程的 MDC 追踪标识，防止线程池复用污染
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    private static boolean isValidTraceId(String traceId, int maxLength) {
        if (traceId == null || traceId.isEmpty() || traceId.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < traceId.length(); i++) {
            char c = traceId.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private boolean isShutdownControlPlaneRequest(HttpServletRequest request) {
        WebProperties.GracefulShutdownProperties shutdownProps =
                webProperties != null ? webProperties.getShutdown() : null;
        if (shutdownProps != null && !shutdownProps.isEnabled()) {
            return false;
        }
        String path = resolveNormalizedPath(request);
        if (DEFAULT_MVC_PRE_STOP_PATH.equals(path) || ACTUATOR_PRE_STOP_PATH.equals(path)) {
            return true;
        }
        String configuredMvcPath = shutdownProps != null ? shutdownProps.getMvcEndpointPath() : null;
        if (configuredMvcPath != null && !configuredMvcPath.isBlank()) {
            String normalizedConfigured = normalizePath(configuredMvcPath);
            return !"/".equals(normalizedConfigured) && normalizedConfigured.equals(path);
        }
        return false;
    }

    private static String resolveNormalizedPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null || requestUri.isEmpty()) {
            return "";
        }
        String contextPath = request.getContextPath();
        boolean hasValidContextPath = contextPath != null
                && !contextPath.isEmpty()
                && !"/".equals(contextPath)
                && (requestUri.equals(contextPath) || requestUri.startsWith(contextPath + "/"));
        String path = hasValidContextPath
                ? requestUri.substring(contextPath.length())
                : requestUri;
        return normalizePath(path);
    }

    private static String normalizePath(String rawPath) {
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return "/";
        }
        // 按段清洗矩阵参数（;...）并折叠连续斜杠，避免中间段矩阵参数截断整条 URI 或前缀边界误判
        StringBuilder normalized = new StringBuilder(trimmed.length());
        int start = 0;
        int len = trimmed.length();
        while (start < len) {
            int slash = trimmed.indexOf('/', start);
            int end = slash >= 0 ? slash : len;
            int semicolon = trimmed.indexOf(';', start);
            if (semicolon >= start && semicolon < end) {
                end = semicolon;
            }
            if (end > start) {
                normalized.append('/').append(trimmed, start, end);
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return normalized.isEmpty() ? "/" : normalized.toString();
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
