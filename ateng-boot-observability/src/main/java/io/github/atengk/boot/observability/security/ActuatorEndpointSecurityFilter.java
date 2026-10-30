package io.github.atengk.boot.observability.security;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.observability.event.ActuatorAccessDeniedEvent;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Actuator 生产端点高优先级安全守卫过滤器
 * <p>
 * 部署于 Servlet 容器最前沿，专职拦截 Actuator 端点请求并构建双层立体生产安全加固体系：
 * 1. 黄金探针与度量白名单免密放行：{@code /health}（含 {@code /liveness}、{@code /readiness}）、{@code /info}、{@code /prometheus} 与 {@code /pre-stop}；
 * 2. 第一层（网络层）：基于纯 JDK 17 原生位运算与 {@link CidrMatcher} 实现内网 IP / CIDR 网段白名单免密放行（{@code allowed-ip-ranges}）；
 * 3. 第二层（凭据层）：未命中白名单的外网请求，支持 {@code X-Actuator-Token} 或 {@code Authorization: Basic} 双重认证互斥核验（OR 逻辑）；
 * 4. 恒定时间防时序攻击（Timing Attack）对比凭证，未授权或凭据不匹配时阻断返回 HTTP 403 并发布 {@link ActuatorAccessDeniedEvent} 领域事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class ActuatorEndpointSecurityFilter extends OncePerRequestFilter implements Ordered, ApplicationEventPublisherAware {

    /**
     * 过滤器优先级：高优先级守卫，优先于大部分业务拦截器
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /**
     * 授权凭证请求头名称
     */
    public static final String ACTUATOR_TOKEN_HEADER = "X-Actuator-Token";

    /**
     * HTTP Basic Auth 请求头名称
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * 默认 Actuator 端点基础路径
     */
    public static final String DEFAULT_BASE_PATH = "/actuator";

    private static final String[] IP_HEADER_CANDIDATES = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    };

    private final ObservabilityProperties properties;

    @Getter
    private final String basePath;

    private ApplicationEventPublisher eventPublisher;

    public ActuatorEndpointSecurityFilter(ObservabilityProperties properties) {
        this(properties, DEFAULT_BASE_PATH, null);
    }

    public ActuatorEndpointSecurityFilter(ObservabilityProperties properties, String basePath) {
        this(properties, basePath, null);
    }

    public ActuatorEndpointSecurityFilter(ObservabilityProperties properties, String basePath, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.basePath = normalizeBasePath(basePath);
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        if (this.eventPublisher == null) {
            this.eventPublisher = applicationEventPublisher;
        }
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

        // 非 Actuator 端点请求，直接放行（严格比对路径全等或带斜杠子路径，防止误伤 /actuators 等常规业务端点）
        if (!path.equals(this.basePath) && !path.startsWith(this.basePath + "/")) {
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

        // 提取客户端真实 IP
        String clientIp = extractClientIp(request);

        // 3. 第一层（网络层）：CIDR IP 白名单免密放行
        List<String> allowedIpRanges = (security != null) ? security.getAllowedIpRanges() : Collections.emptyList();
        if (allowedIpRanges != null && !allowedIpRanges.isEmpty() && CidrMatcher.matchesAny(clientIp, allowedIpRanges)) {
            log.debug("Actuator 端点安全放行：来源 IP [{}] 命中白名单网段，允许访问 [{}]", clientIp, path);
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 第二层（凭据层）：Token OR Basic Auth 互斥核验
        String configuredToken = (security != null) ? security.getToken() : null;
        ObservabilityProperties.BasicAuthProperties basicAuth = (security != null) ? security.getBasicAuth() : null;

        String requestToken = request.getHeader(ACTUATOR_TOKEN_HEADER);
        boolean tokenValid = isTokenValid(requestToken, configuredToken);

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        boolean basicAuthValid = isBasicAuthValid(authHeader, basicAuth);

        if (tokenValid || basicAuthValid) {
            log.debug("Actuator 端点安全放行：凭证核验通过 (tokenValid={}, basicAuthValid={})，允许访问 [{}]",
                    tokenValid, basicAuthValid, path);
            filterChain.doFilter(request, response);
            return;
        }

        // 5. 鉴权失败阻断：确定失败原因并发布安全审计事件
        String reason;
        if (requestToken != null && !requestToken.isBlank()) {
            reason = "未授权访问高危端点：X-Actuator-Token 凭据缺失或不匹配";
        } else if (authHeader != null && authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            reason = "未授权访问高危端点：Basic Auth 账号密码不匹配";
        } else if ((configuredToken == null || configuredToken.isBlank()) && (basicAuth == null || !basicAuth.isEnabled())) {
            reason = "高危敏感端点已加固锁定，未配置授权 Token 拒绝访问";
        } else {
            reason = "未授权访问高危端点：来源 IP 未在白名单且未提供有效授权凭据";
        }

        log.warn("Actuator 端点安全拦截：高危端点 [{}] 访问拒绝，来源 IP: [{}]，原因: {}", path, clientIp, reason);
        publishAccessDeniedEvent(path, clientIp, reason);
        rejectAccess(response, reason);
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
     * 恒定时间 Token 凭据比对，防御时序分析攻击
     */
    private boolean isTokenValid(String requestToken, String configuredToken) {
        if (requestToken == null || requestToken.isBlank() || configuredToken == null || configuredToken.isBlank()) {
            return false;
        }
        byte[] a = requestToken.strip().getBytes(StandardCharsets.UTF_8);
        byte[] b = configuredToken.strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * 恒定时间 HTTP Basic Auth 账号密码比对，防御时序分析攻击
     */
    private boolean isBasicAuthValid(String authHeader, ObservabilityProperties.BasicAuthProperties basicAuth) {
        if (basicAuth == null || !basicAuth.isEnabled()) {
            return false;
        }
        if (authHeader == null || authHeader.isBlank()) {
            return false;
        }
        if (!authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String base64Credentials = authHeader.substring(6).trim();
        if (base64Credentials.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(decoded, StandardCharsets.UTF_8);
            int colonIndex = credentials.indexOf(':');
            if (colonIndex < 0) {
                return false;
            }
            String username = credentials.substring(0, colonIndex);
            String password = credentials.substring(colonIndex + 1);

            String configuredUsername = basicAuth.getUsername();
            String configuredPassword = basicAuth.getPassword();
            if (configuredUsername == null || configuredUsername.isBlank()
                    || configuredPassword == null || configuredPassword.isBlank()) {
                return false;
            }

            byte[] userA = username.getBytes(StandardCharsets.UTF_8);
            byte[] userB = configuredUsername.getBytes(StandardCharsets.UTF_8);
            boolean userMatched = MessageDigest.isEqual(userA, userB);

            byte[] passA = password.getBytes(StandardCharsets.UTF_8);
            byte[] passB = configuredPassword.getBytes(StandardCharsets.UTF_8);
            boolean passMatched = MessageDigest.isEqual(passA, passB);

            return userMatched && passMatched;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 提取客户端真实 IP 地址（优先解析反向代理请求头）
     */
    private static String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        for (String header : IP_HEADER_CANDIDATES) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value.trim())) {
                int commaIndex = value.indexOf(',');
                if (commaIndex >= 0) {
                    value = value.substring(0, commaIndex).trim();
                } else {
                    value = value.trim();
                }
                if (!value.isEmpty() && !"unknown".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr.trim() : "";
    }

    /**
     * 发布未授权访问拦截领域事件
     */
    private void publishAccessDeniedEvent(String uri, String clientIp, String reason) {
        if (this.eventPublisher != null) {
            try {
                this.eventPublisher.publishEvent(new ActuatorAccessDeniedEvent(this, uri, clientIp, reason));
            } catch (Exception ex) {
                log.error("发布 ActuatorAccessDeniedEvent 事件失败: uri={}, clientIp={}", uri, clientIp, ex);
            }
        }
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
