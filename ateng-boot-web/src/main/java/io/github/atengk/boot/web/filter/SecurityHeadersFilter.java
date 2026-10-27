package io.github.atengk.boot.web.filter;

import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * HTTP 安全响应头防御与容器指纹清洗过滤器
 * 开箱即用注入 OWASP ASVS 与等保三级推荐标准基线安全响应头，
 * 防御 MIME 类型嗅探、点击劫持、跨站反射与敏感 Referrer 泄露，
 * 采用 containsHeader 前置守卫防止覆盖上游/下游业务自定义标头，并主动擦除容器 Server 指纹
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * 过滤器执行优先级，紧随链路追踪与运行时监控过滤器之后
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 4;

    public static final String HEADER_X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String HEADER_X_FRAME_OPTIONS = "X-Frame-Options";
    public static final String HEADER_X_XSS_PROTECTION = "X-XSS-Protection";
    public static final String HEADER_REFERRER_POLICY = "Referrer-Policy";
    public static final String HEADER_CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    public static final String HEADER_PERMISSIONS_POLICY = "Permissions-Policy";
    public static final String HEADER_SERVER = "Server";

    public static final String VALUE_NOSNIFF = "nosniff";

    private final WebProperties.SecurityHeadersProperties properties;

    public SecurityHeadersFilter(WebProperties.SecurityHeadersProperties properties) {
        this.properties = Objects.requireNonNull(properties, "SecurityHeadersProperties must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 若全局未启用，直接放行
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 注入配置的基线安全响应头（containsHeader 守卫防覆盖）
        injectSecurityHeaders(response);

        // 3. 包装响应对象以擦除或隐藏 Server 标头指纹
        HttpServletResponse effectiveResponse = properties.isRemoveServerHeader()
                ? new SecurityHeadersResponseWrapper(response, true)
                : response;

        // 4. 执行后续过滤器链与 Servlet
        filterChain.doFilter(request, effectiveResponse);
    }

    private void injectSecurityHeaders(HttpServletResponse response) {
        // (1) X-Content-Type-Options: nosniff
        if (properties.isContentTypeOptions() && !response.containsHeader(HEADER_X_CONTENT_TYPE_OPTIONS)) {
            response.setHeader(HEADER_X_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
        }
        // (2) X-Frame-Options
        if (StringUtils.hasText(properties.getFrameOptions()) && !response.containsHeader(HEADER_X_FRAME_OPTIONS)) {
            response.setHeader(HEADER_X_FRAME_OPTIONS, properties.getFrameOptions());
        }
        // (3) X-XSS-Protection
        if (StringUtils.hasText(properties.getXssProtection()) && !response.containsHeader(HEADER_X_XSS_PROTECTION)) {
            response.setHeader(HEADER_X_XSS_PROTECTION, properties.getXssProtection());
        }
        // (4) Referrer-Policy
        if (StringUtils.hasText(properties.getReferrerPolicy()) && !response.containsHeader(HEADER_REFERRER_POLICY)) {
            response.setHeader(HEADER_REFERRER_POLICY, properties.getReferrerPolicy());
        }
        // (5) Content-Security-Policy (可选)
        if (StringUtils.hasText(properties.getContentSecurityPolicy()) && !response.containsHeader(HEADER_CONTENT_SECURITY_POLICY)) {
            response.setHeader(HEADER_CONTENT_SECURITY_POLICY, properties.getContentSecurityPolicy());
        }
        // (6) Permissions-Policy (可选)
        if (StringUtils.hasText(properties.getPermissionsPolicy()) && !response.containsHeader(HEADER_PERMISSIONS_POLICY)) {
            response.setHeader(HEADER_PERMISSIONS_POLICY, properties.getPermissionsPolicy());
        }
    }

    /**
     * 安全响应包装器，专职拦截和抹除 Server 指纹头
     */
    static class SecurityHeadersResponseWrapper extends HttpServletResponseWrapper {

        private final boolean removeServerHeader;

        public SecurityHeadersResponseWrapper(HttpServletResponse response, boolean removeServerHeader) {
            super(response);
            this.removeServerHeader = removeServerHeader;
        }

        @Override
        public void setHeader(String name, String value) {
            if (removeServerHeader && HEADER_SERVER.equalsIgnoreCase(name)) {
                return;
            }
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (removeServerHeader && HEADER_SERVER.equalsIgnoreCase(name)) {
                return;
            }
            super.addHeader(name, value);
        }

        @Override
        public boolean containsHeader(String name) {
            if (removeServerHeader && HEADER_SERVER.equalsIgnoreCase(name)) {
                return false;
            }
            return super.containsHeader(name);
        }

        @Override
        public String getHeader(String name) {
            if (removeServerHeader && HEADER_SERVER.equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Collection<String> getHeaders(String name) {
            if (removeServerHeader && HEADER_SERVER.equalsIgnoreCase(name)) {
                return Collections.emptyList();
            }
            return super.getHeaders(name);
        }

        @Override
        public Collection<String> getHeaderNames() {
            Collection<String> names = super.getHeaderNames();
            if (!removeServerHeader || names == null || names.isEmpty()) {
                return names;
            }
            return names.stream()
                    .filter(name -> !HEADER_SERVER.equalsIgnoreCase(name))
                    .toList();
        }
    }
}
