package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfiguration;
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
import java.util.regex.Pattern;

/**
 * SBA 控制台只读模式与高危写操作安全守卫过滤器
 * 当 enableWriteOperations = false 时，在前置边界拦截针对微服务 Actuator 代理端点（/instances/{id}/actuator/**）的非幂等写操作，
 * 严格杜绝在只读管控策略下动态篡改微服务日志级别、重载配置或触发容器下线
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class AdminWriteGuardFilter extends OncePerRequestFilter implements Ordered {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final int order;
    private final AtengAdminServerProperties properties;
    private final String contextPath;
    private final Pattern actuatorProxyPattern;

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
        this.contextPath = AtengAdminServerAutoConfiguration.normalizeContextPath(
                adminServerProperties != null ? adminServerProperties.getContextPath() : null
        );
        String prefix = this.contextPath.isEmpty() ? "" : Pattern.quote(this.contextPath);
        this.actuatorProxyPattern = Pattern.compile("^" + prefix + "/instances/[^/]+/actuator(?:/.*)?$");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // 仅当配置显式关闭写操作（只读模式）且当前请求为写操作方法时执行拦截判定
        if (shouldBlock(method, uri)) {
            log.warn("[Write Guard] 控制台只读拦截：当前处于只读管控模式，阻断针对微服务 Actuator 端点的变更请求：method=[{}], uri=[{}]", method, uri);
            reject(response, HttpStatus.FORBIDDEN, "Console write operations are disabled by policy");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 判断指定 HTTP 方法与请求路径在当前策略下是否应当被只读守卫阻断
     *
     * @param method HTTP 请求方法
     * @param uri    目标请求路径
     * @return true 若会被只读守卫拦截返回 403
     */
    public boolean shouldBlock(String method, String uri) {
        return properties != null && !properties.isEnableWriteOperations()
                && method != null && MUTATING_METHODS.contains(method.toUpperCase(Locale.ROOT))
                && isActuatorProxyEndpoint(uri);
    }

    /**
     * 判断当前请求路径是否为微服务 Actuator 反向代理端点（/instances/{id}/actuator/**）
     * 严格放行客户端自身的注册（POST /instances）与下线（DELETE /instances/{id}）
     *
     * @param uri 请求路径
     * @return true 若为 Actuator 反向代理端点
     */
    public boolean isActuatorProxyEndpoint(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        return actuatorProxyPattern.matcher(uri).matches();
    }

    /**
     * 输出 JSON 格式拒绝响应
     */
    private void reject(HttpServletResponse response, HttpStatus status, String msg) throws IOException {
        AdminSecurityResponseUtil.writeJsonResponse(response, status, msg);
    }
}
