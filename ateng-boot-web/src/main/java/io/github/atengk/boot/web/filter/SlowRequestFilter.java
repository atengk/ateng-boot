package io.github.atengk.boot.web.filter;

import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import io.github.atengk.boot.web.trace.TraceUtil;
import io.github.atengk.boot.web.util.IpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 长耗时慢请求性能主动告警过滤器
 * 拦截单次执行耗时超过配置阈值 threshold 的 HTTP 请求并输出格式化 WARN 日志
 * 自动放行 WebSocket 升级握手、SSE 事件流、文件流下载以及异步挂起请求，从源头杜绝长连接误报
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class SlowRequestFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestFilter.class);

    /**
     * 默认过滤器执行优先级：位于 TraceFilter 与可重读管道之后
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 3;

    private static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(3);
    private static final String UPGRADE_HEADER = "Upgrade";
    private static final String CONNECTION_HEADER = "Connection";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final String OCTET_STREAM_CONTENT_TYPE = "application/octet-stream";

    private final Duration threshold;
    private final List<String> excludeUrls;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public SlowRequestFilter() {
        this(DEFAULT_THRESHOLD, Collections.emptyList());
    }

    public SlowRequestFilter(Duration threshold) {
        this(threshold, Collections.emptyList());
    }

    public SlowRequestFilter(WebProperties.SlowRequestProperties properties) {
        this(
                properties != null && properties.getThreshold() != null ? properties.getThreshold() : DEFAULT_THRESHOLD,
                properties != null && properties.getExcludeUrls() != null ? properties.getExcludeUrls() : Collections.emptyList()
        );
    }

    public SlowRequestFilter(Duration threshold, List<String> excludeUrls) {
        this.threshold = threshold != null ? threshold : DEFAULT_THRESHOLD;
        this.excludeUrls = excludeUrls != null ? new ArrayList<>(excludeUrls) : new ArrayList<>();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    public Duration getThreshold() {
        return threshold;
    }

    public List<String> getExcludeUrls() {
        return Collections.unmodifiableList(excludeUrls);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isExcluded(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!shouldSkip(request, response)) {
                long costTime = System.currentTimeMillis() - startTime;
                long thresholdMillis = threshold.toMillis();
                if (thresholdMillis >= 0 && costTime >= thresholdMillis) {
                    logSlowRequest(request, response, costTime, thresholdMillis);
                }
            }
        }
    }

    /**
     * 判断当前请求是否匹配排除路径白名单
     *
     * @param request HTTP 请求
     * @return true 表示命中白名单需要排除
     */
    protected boolean isExcluded(HttpServletRequest request) {
        if (excludeUrls.isEmpty()) {
            return false;
        }
        String uri = request.getRequestURI();
        String servletPath = request.getServletPath();
        for (String pattern : excludeUrls) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
            if (servletPath != null && pathMatcher.match(pattern, servletPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定是否应跳过慢请求日志记录（长连接、流式传输或异步挂起请求）
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return true 表示需要跳过日志输出
     */
    protected boolean shouldSkip(HttpServletRequest request, HttpServletResponse response) {
        // 1. 异步调度与挂起请求跳过
        if (request.isAsyncStarted()) {
            return true;
        }
        // 2. WebSocket 升级协议跳过
        if (isWebSocket(request, response)) {
            return true;
        }
        // 3. SSE 事件流 (text/event-stream) 跳过
        if (isEventStream(request, response)) {
            return true;
        }
        // 4. 二进制与文件流 (application/octet-stream) 跳过
        if (isOctetStream(request, response)) {
            return true;
        }
        return false;
    }

    private boolean isWebSocket(HttpServletRequest request, HttpServletResponse response) {
        String reqUpgrade = request.getHeader(UPGRADE_HEADER);
        if (reqUpgrade != null && !reqUpgrade.isBlank()) {
            return true;
        }
        String respUpgrade = response.getHeader(UPGRADE_HEADER);
        if (respUpgrade != null && !respUpgrade.isBlank()) {
            return true;
        }
        String connection = request.getHeader(CONNECTION_HEADER);
        return connection != null && connection.toLowerCase().contains("upgrade");
    }

    private boolean isEventStream(HttpServletRequest request, HttpServletResponse response) {
        String respContentType = response.getContentType();
        if (respContentType != null && respContentType.toLowerCase().contains(SSE_CONTENT_TYPE)) {
            return true;
        }
        String reqAccept = request.getHeader(ACCEPT_HEADER);
        if (reqAccept != null && reqAccept.toLowerCase().contains(SSE_CONTENT_TYPE)) {
            return true;
        }
        String reqContentType = request.getContentType();
        return reqContentType != null && reqContentType.toLowerCase().contains(SSE_CONTENT_TYPE);
    }

    private boolean isOctetStream(HttpServletRequest request, HttpServletResponse response) {
        String respContentType = response.getContentType();
        if (respContentType != null && respContentType.toLowerCase().contains(OCTET_STREAM_CONTENT_TYPE)) {
            return true;
        }
        String reqAccept = request.getHeader(ACCEPT_HEADER);
        if (reqAccept != null && reqAccept.toLowerCase().contains(OCTET_STREAM_CONTENT_TYPE)) {
            return true;
        }
        String reqContentType = request.getContentType();
        return reqContentType != null && reqContentType.toLowerCase().contains(OCTET_STREAM_CONTENT_TYPE);
    }

    private void logSlowRequest(HttpServletRequest request, HttpServletResponse response, long costTime, long thresholdMillis) {
        String method = request.getMethod() != null ? request.getMethod() : "UNKNOWN";
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        String clientIp = IpUtil.getClientIp(request);
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = "-";
        }
        String traceId = TraceUtil.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader("X-Trace-Id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = "-";
        }

        log.warn("[SLOW_REQUEST] 检测到慢 HTTP 请求: method=[{}], uri=[{}], cost=[{}ms], threshold=[{}ms], clientIp=[{}], traceId=[{}]",
                method, uri, costTime, thresholdMillis, clientIp, traceId);
    }
}
