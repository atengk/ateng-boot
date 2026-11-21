package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.Optional;

/**
 * SBA 原生 UI 请求身份桥接过滤器
 * <p>
 * 严格限定于 {@code {contextPath}/**} 作用域内，将 Sa-Token 会话中承载的 {@link AdminConsolePrincipal}
 * 注入 {@link HttpServletRequest#getUserPrincipal()}，打通 SBA 原生前端（{@code UiController.getUser(Principal)}）
 * 的右上角当前用户名展示与登出交互，同时杜绝对宿主业务接口的侵入。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Order(AdminPrincipalBridgeFilter.DEFAULT_ORDER)
public class AdminPrincipalBridgeFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 默认执行优先级：位于 SaServletFilter（-100）之后、AdminEndpointGuardFilter（-90）之前
     */
    public static final int DEFAULT_ORDER = -95;

    private final String adminContextPath;
    private final int order;

    public AdminPrincipalBridgeFilter(AdminServerProperties adminServerProperties) {
        this(adminServerProperties, DEFAULT_ORDER);
    }

    public AdminPrincipalBridgeFilter(AdminServerProperties adminServerProperties, int order) {
        this.adminContextPath = AdminPathMatcher.normalizeContextPath(
                adminServerProperties != null ? adminServerProperties.getContextPath() : null
        );
        this.order = order;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!AdminPathMatcher.isWithinAdminContext(request.getRequestURI(), request.getContextPath(), adminContextPath)) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<AdminConsolePrincipal> principal = AdminSessionHelper.getCurrentPrincipal();
        if (principal.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        Principal bridged = principal.get();
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public Principal getUserPrincipal() {
                return bridged;
            }
        };
        filterChain.doFilter(wrapped, response);
    }
}
