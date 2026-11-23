package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * SBA 控制台只读模式与高危写操作安全守卫过滤器（向后兼容基类）
 * 当 enableWriteOperations = false 时，拦截针对微服务 Actuator 代理端点（/instances/{id}/actuator/** 与 /applications/{app}/actuator/**）的非幂等写操作，
 * 严格杜绝在只读管控策略下动态篡改微服务日志级别、重载配置或触发容器下线。
 * <p>
 * 演进说明：新版本高危端点黑名单与 RBAC 角色校验由 {@link AdminEndpointGuardFilter} 继承扩展承担，本类保持全部公开方法与构造签名向后兼容。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class AdminWriteGuardFilter extends OncePerRequestFilter implements Ordered {

    protected static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final int order;
    protected final AtengAdminServerProperties properties;
    protected final String contextPath;

    @Override
    public int getOrder() {
        return this.order;
    }

    public AdminWriteGuardFilter(AtengAdminServerProperties properties, AdminServerProperties adminServerProperties) {
        this(properties, adminServerProperties, Ordered.HIGHEST_PRECEDENCE + 60);
    }

    public AdminWriteGuardFilter(AtengAdminServerProperties properties, AdminServerProperties adminServerProperties, int order) {
        this.properties = properties;
        this.order = order;
        this.contextPath = AdminPathMatcher.normalizeContextPath(
                adminServerProperties != null ? adminServerProperties.getContextPath() : null
        );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String normalizedUri = AdminPathMatcher.normalizeRequestPath(request.getRequestURI(), request.getContextPath());

        // 仅当配置显式关闭写操作（只读模式）且当前请求为写操作方法时执行拦截判定
        if (shouldBlock(method, normalizedUri)) {
            log.warn("[Write Guard] 控制台只读拦截：当前处于只读管控模式，阻断针对微服务 Actuator 端点的变更请求：method=[{}], uri=[{}]",
                    method, request.getRequestURI());
            reject(response, HttpStatus.FORBIDDEN, "Console write operations are disabled by policy");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 判断指定 HTTP 方法与请求路径在当前策略下是否应当被守卫阻断
     *
     * @param method HTTP 请求方法
     * @param uri    目标请求路径
     * @return true 若会被守卫拦截返回 403
     */
    public boolean shouldBlock(String method, String uri) {
        return properties != null && !properties.isEnableWriteOperations()
                && isMutatingMethod(method)
                && isActuatorProxyEndpoint(uri);
    }

    /**
     * 判断当前请求路径是否为微服务 Actuator 反向代理端点（/instances/{id}/actuator/** 或 /applications/{app}/actuator/**）
     * 严格放行客户端自身的注册（POST /instances）与下线（DELETE /instances/{id}）
     *
     * @param uri 请求路径
     * @return true 若为 Actuator 反向代理端点
     */
    public boolean isActuatorProxyEndpoint(String uri) {
        return AdminPathMatcher.isActuatorProxyEndpoint(uri, "", contextPath);
    }

    protected static boolean isMutatingMethod(String method) {
        return method != null && MUTATING_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * 输出 JSON 格式拒绝响应
     */
    protected void reject(HttpServletResponse response, HttpStatus status, String msg) throws IOException {
        AdminSecurityResponseUtil.writeJsonResponse(response, status, msg);
    }
}
