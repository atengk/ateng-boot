package io.github.atengk.boot.observability.security;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Actuator 生产端点高优先级安全守卫过滤器
 * <p>
 * 部署于 Servlet 容器最前沿，专职拦截 Actuator 端点请求并实施细粒度生产加固：
 * 1. 黄金探针与度量白名单免密放行：{@code /health}（含 {@code /liveness}、{@code /readiness}）、{@code /info}、{@code /prometheus} 与 {@code /pre-stop}；
 * 2. 高危敏感端点锁定：当启用加固守卫（{@code lockdown-enabled=true}）时，对 {@code /env}、{@code /beans}、{@code /heapdump}、{@code /threaddump} 等高危端点强制核验 {@code X-Actuator-Token} 请求头凭据；
 * 3. 恒定时间防时序攻击（Timing Attack）对比凭证，未授权或凭据不匹配时直接阻断返回 HTTP 403，坚决杜绝线上生产环境机密配置与 Bean 拓扑泄露。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class ActuatorEndpointSecurityFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 过滤器优先级：高优先级守卫，优先于大部分业务拦截器
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /**
     * 授权凭证请求头名称
     */
    public static final String ACTUATOR_TOKEN_HEADER = "X-Actuator-Token";

    /**
     * 默认 Actuator 端点基础路径
     */
    public static final String DEFAULT_BASE_PATH = "/actuator";

    private final ObservabilityProperties properties;

    @Getter
    private final String basePath;

    public ActuatorEndpointSecurityFilter(ObservabilityProperties properties) {
        this(properties, DEFAULT_BASE_PATH);
    }

    public ActuatorEndpointSecurityFilter(ObservabilityProperties properties, String basePath) {
        this.properties = properties;
        this.basePath = normalizeBasePath(basePath);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;

        // 深度防卫：清洗多余斜杠，防止路径混淆绕过（如 /actuator//env）
        if (path != null) {
            path = path.replaceAll("/+", "/");
        } else {
            path = "";
        }

        // 非 Actuator 端点请求，直接放行
        if (!path.startsWith(this.basePath)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 提取 Actuator 相对端点路径（如 /actuator/health -> /health）
        String subPath = path.substring(this.basePath.length());
        if (subPath.endsWith("/") && subPath.length() > 1) {
            subPath = subPath.substring(0, subPath.length() - 1);
        }

        // 1. 白名单黄金端点免密放行
        if (isWhitelisted(subPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 检查加固守卫开关
        ObservabilityProperties.SecurityProperties security = (properties != null) ? properties.getSecurity() : null;
        boolean lockdownEnabled = (security == null) || security.isLockdownEnabled();
        if (!lockdownEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 敏感高危端点凭据核验
        String configuredToken = (security != null) ? security.getToken() : null;
        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("Actuator 端点安全拦截：高危端点 [{}] 处于加固状态且未配置授权 Token，拒绝所有外部访问", path);
            rejectAccess(response, "高危敏感端点已加固锁定，未配置授权 Token 拒绝访问");
            return;
        }

        String requestToken = request.getHeader(ACTUATOR_TOKEN_HEADER);
        if (!isTokenValid(requestToken, configuredToken)) {
            log.warn("Actuator 端点安全拦截：高危端点 [{}] 请求凭据缺失或无效", path);
            rejectAccess(response, "未授权访问高危端点：X-Actuator-Token 凭据缺失或不匹配");
            return;
        }

        // 凭证校验通过，放行
        filterChain.doFilter(request, response);
    }

    /**
     * 判断是否为白名单黄金端点
     */
    private boolean isWhitelisted(String subPath) {
        if (subPath == null || subPath.isEmpty() || "/".equals(subPath)) {
            // /actuator 根索引端点包含全量已暴露端点元数据列表，加固模式下需鉴权
            return false;
        }
        // 健康探针体系全量放行（包含 liveness、readiness 及各类子探针）
        if (subPath.equals("/health") || subPath.startsWith("/health/")) {
            return true;
        }
        // 应用基础信息端点放行
        if (subPath.equals("/info") || subPath.startsWith("/info/")) {
            return true;
        }
        // Prometheus 指标抓取端点放行
        if (subPath.equals("/prometheus") || subPath.startsWith("/prometheus/")) {
            return true;
        }
        // 平滑停机流量排空端点放行
        if (subPath.equals("/pre-stop") || subPath.startsWith("/pre-stop/")) {
            return true;
        }
        return false;
    }

    /**
     * 恒定时间凭据比对，防御时序分析攻击
     */
    private boolean isTokenValid(String requestToken, String configuredToken) {
        if (requestToken == null || requestToken.isBlank() || configuredToken == null) {
            return false;
        }
        byte[] a = requestToken.strip().getBytes(StandardCharsets.UTF_8);
        byte[] b = configuredToken.strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * 阻断未授权请求并返回统一 HTTP 403 规范 JSON
     */
    private void rejectAccess(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Result<?> result = Result.fail(403, "Actuator 端点访问拒绝: " + message);
        response.getWriter().write(Objects.requireNonNullElse(JsonUtil.toJsonString(result), "{\"code\":403,\"msg\":\"Forbidden\"}"));
        response.getWriter().flush();
    }

    /**
     * 规整基础路径，去除尾部多余斜杠
     */
    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return DEFAULT_BASE_PATH;
        }
        String normalized = basePath.strip();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? DEFAULT_BASE_PATH : normalized;
    }
}
