package io.github.atengk.boot.web.ip.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.ip.IpAccessMode;
import io.github.atengk.boot.web.ip.properties.IpAccessControlProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * URL 路径级 IP 访问控制过滤器
 * 在请求进入 Controller 前实施客户端真实 IP 与 CIDR 子网黑白名单快速校验与安全阻断
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
public class IpAccessControlFilter extends OncePerRequestFilter implements Ordered {

    public static final String DENIED_HEADER = "X-Denied-Reason";
    public static final String DENIED_REASON = "IP_BLOCKED";

    /**
     * 过滤器执行优先级：置于 TraceFilter 之后，保证 MDC traceId 已初始化
     */
    public static final int ORDER = TraceFilter.ORDER + 1;

    private final IpAccessControlProperties properties;

    public IpAccessControlFilter(IpAccessControlProperties properties) {
        this.properties = properties != null ? properties : new IpAccessControlProperties();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        IpAccessControlProperties.Filter filterProps = properties.getFilter();
        if (!properties.isEnabled() || !filterProps.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. 检查排除 URL 列表
        if (isExcluded(request, filterProps.getExcludeUrls())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 检查拦截 URL 列表（若未配置 urlPatterns，则默认匹配全路径）
        if (!isMatchedUrl(request, filterProps.getUrlPatterns())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 提取客户端 IP 并判定规则
        String clientIp = IpUtil.getClientIp(request);
        IpAccessMode mode = filterProps.getEffectiveMode(properties.getMode());
        List<String> rules = filterProps.getEffectiveRules(properties.getRules());

        boolean matched = IpUtil.isInRange(clientIp, rules);
        boolean blocked = false;

        if (mode == IpAccessMode.WHITE_LIST) {
            if (!matched) {
                blocked = true;
            }
        } else if (mode == IpAccessMode.BLACK_LIST) {
            if (matched) {
                blocked = true;
            }
        }

        if (blocked) {
            log.warn("IpAccessControlFilter blocked request from IP [{}] on URI [{}] (mode={}, rules={})",
                    clientIp, request.getRequestURI(), mode, rules);
            response.setHeader(DENIED_HEADER, DENIED_REASON);
            if (properties.isHttpStatus403()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
            Result<Void> result = Result.fail(CommonResultCode.FORBIDDEN.getCode(), properties.getDefaultDeniedMessage());
            ServletUtil.writeJson(response, JsonUtil.toJsonString(result));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExcluded(HttpServletRequest request, List<String> excludeUrls) {
        return ServletUtil.matchesAnyPath(request, excludeUrls);
    }

    private boolean isMatchedUrl(HttpServletRequest request, List<String> urlPatterns) {
        if (urlPatterns == null || urlPatterns.isEmpty()) {
            return true;
        }
        return ServletUtil.matchesAnyPath(request, urlPatterns);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    public IpAccessControlProperties getProperties() {
        return properties;
    }
}
