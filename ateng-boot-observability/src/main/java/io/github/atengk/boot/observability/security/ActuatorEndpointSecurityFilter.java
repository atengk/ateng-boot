package io.github.atengk.boot.observability.security;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.CidrMatcher;
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
import java.util.regex.Pattern;

/**
 * Actuator 生产端点高优先级安全守卫过滤器
 * <p>
 * 部署于 Servlet 容器最前沿，专职拦截 Actuator 端点请求并构建双层立体生产安全加固体系：
 * 1. 可配置黄金探针与度量白名单免密放行（默认：{@code /health}、{@code /info}、{@code /prometheus} 与 {@code /pre-stop}），{@code /actuator} 根索引端点始终受拦截保护；
 * 2. 第一层（网络层）：默认严格采信网络层 {@code RemoteAddr}，仅当开启 {@code trust-proxy-headers} 或直连命中 {@code trusted-proxies} 时解析代理请求头，并基于 {@link io.github.atengk.boot.core.util.CidrMatcher} 实现内网 IP / CIDR 网段白名单免密放行（{@code allowed-ip-ranges}）；
 * 3. 第二层（凭据层）：未命中白名单的外网请求，支持 {@code X-Actuator-Token} 或 {@code Authorization: Basic} 双重认证互斥核验（OR 逻辑）；
 * 4. 恒定时间防时序攻击（Timing Attack）对比凭证，未授权或凭据不匹配时支持常规 HTTP 403 与隐身 HTTP 404（{@code stealth-mode}）阻断响应，并统一发布 {@link ActuatorAccessDeniedEvent} 领域事件。
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

    private static final Pattern MULTI_SLASH_PATTERN = Pattern.compile("/+");

    private static final List<String> DEFAULT_WHITELISTED_ENDPOINTS = List.of(
            "health",
            "info",
            "prometheus",
            "pre-stop"
    );

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
            path = MULTI_SLASH_PATTERN.matcher(path).replaceAll("/");
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

        ObservabilityProperties.SecurityProperties security = (properties != null) ? properties.getSecurity() : null;

        // 1. 可配置白名单黄金端点免密放行（/actuator 根索引端点始终受安全拦截保护）
        if (isWhitelisted(subPath, security)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 检查加固守卫开关
        boolean lockdownEnabled = (security == null) || security.isLockdownEnabled();
        if (!lockdownEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // 提取客户端真实 IP（默认仅采信网络层 RemoteAddr，仅在显式信任或直连命中 trustedProxies 时解析代理头）
        String clientIp = extractClientIp(request, security);

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
        boolean stealthMode = (security != null) && security.isStealthMode();
        rejectAccess(response, reason, stealthMode);
    }

    /**
     * 判断是否为免密白名单端点（基于 {@code whitelistedEndpoints} 标准化配置匹配）
     */
    private boolean isWhitelisted(String subPath, ObservabilityProperties.SecurityProperties security) {
        if (subPath == null || subPath.isEmpty() || "/".equals(subPath)) {
            // /actuator 根索引端点包含全量已暴露端点元数据列表，加固模式下需鉴权
            return false;
        }
        List<String> configured = (security != null) ? security.getWhitelistedEndpoints() : DEFAULT_WHITELISTED_ENDPOINTS;
        if (configured == null || configured.isEmpty()) {
            return false;
        }
        for (String endpoint : configured) {
            String normalizedEndpoint = normalizeEndpointPattern(endpoint);
            if (normalizedEndpoint == null) {
                continue;
            }
            if (subPath.equals(normalizedEndpoint) || subPath.startsWith(normalizedEndpoint + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标准化白名单子路径配置项（剥离首尾空白与多余斜杠，保证以单斜杠开头且非根路径）
     */
    private static String normalizeEndpointPattern(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String normalized = MULTI_SLASH_PATTERN.matcher(endpoint.strip()).replaceAll("/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : "/" + normalized;
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
     * 提取客户端真实 IP 地址
     * <p>
     * 默认仅采信网络层直连 {@code RemoteAddr}；仅当显式开启 {@code trustProxyHeaders}
     * 或直连地址命中 {@code trustedProxies} 白名单时，才依次解析反向代理请求头中的首跳有效 IP。
     * </p>
     */
    private static String extractClientIp(HttpServletRequest request,
                                          ObservabilityProperties.SecurityProperties security) {
        if (request == null) {
            return "";
        }
        String rawRemoteAddr = request.getRemoteAddr();
        String remoteAddr = (rawRemoteAddr != null) ? CidrMatcher.stripPort(rawRemoteAddr.trim()) : "";

        boolean trustProxyHeaders = security != null && security.isTrustProxyHeaders();
        List<String> trustedProxies = (security != null) ? security.getTrustedProxies() : Collections.emptyList();
        boolean fromTrustedProxy = trustedProxies != null && !trustedProxies.isEmpty()
                && !remoteAddr.isEmpty()
                && CidrMatcher.matchesAny(remoteAddr, trustedProxies);

        if (trustProxyHeaders || fromTrustedProxy) {
            for (String header : IP_HEADER_CANDIDATES) {
                String value = request.getHeader(header);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String[] segments = value.split(",");
                for (String segment : segments) {
                    String candidate = CidrMatcher.stripPort(segment.trim());
                    if (candidate != null && !candidate.isEmpty() && !"unknown".equalsIgnoreCase(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return remoteAddr;
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
     * 阻断未授权请求并返回规范 JSON 响应（隐身模式下返回 HTTP 404，常规模式返回 HTTP 403）
     */
    private void rejectAccess(HttpServletResponse response, String message, boolean stealthMode) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        if (stealthMode) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            Result<?> result = Result.fail(404, "Not Found");
            response.getWriter().write(Objects.requireNonNullElse(JsonUtil.toJsonString(result), "{\"code\":404,\"msg\":\"Not Found\"}"));
        } else {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            Result<?> result = Result.fail(403, "Actuator 端点访问拒绝: " + message);
            response.getWriter().write(Objects.requireNonNullElse(JsonUtil.toJsonString(result), "{\"code\":403,\"msg\":\"Forbidden\"}"));
        }
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
