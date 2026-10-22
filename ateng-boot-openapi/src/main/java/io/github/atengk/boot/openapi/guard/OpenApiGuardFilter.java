package io.github.atengk.boot.openapi.guard;

import io.github.atengk.boot.openapi.properties.GuardMode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * OpenAPI 接口文档生产安全守卫过滤器
 * <p>
 * 部署于 Servlet 容器前沿，提供生产级双层安全防御：
 * 1. 物理切断（404）：当 {@code ateng.boot.openapi.enabled=false} 时，直接拦截所有文档与元数据请求并响应 HTTP 404；
 * 2. 运行时凭证守卫：支持 {@code BASIC} 认证（浏览器原生 HTTP 401 弹窗）与 {@code SATOKEN} 分布式会话守卫；
 * 3. 静态资源安全放行：对 CSS、JS、WebJars、图标等静态资源予以白名单放行，保障合法请求的样式正常加载；
 * 4. 防时序攻击：使用 {@link MessageDigest#isEqual(byte[], byte[])} 进行恒定时间凭据比对。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class OpenApiGuardFilter extends OncePerRequestFilter implements Ordered, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGuardFilter.class);

    /**
     * 过滤器优先级：高优先级守卫
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;

    /**
     * Basic Auth 响应头提示语
     */
    public static final String BASIC_REALM_HEADER = "Basic realm=\"OpenAPI Document Guard\"";

    private final OpenApiProperties properties;

    public OpenApiGuardFilter(OpenApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
        if (properties != null && properties.getGuard() != null) {
            GuardMode mode = properties.getGuard().getMode();
            if (mode == GuardMode.SATOKEN) {
                try {
                    Class.forName("cn.dev33.satoken.stp.StpUtil");
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("OpenAPI guard mode is set to SATOKEN, but Sa-Token (cn.dev33.satoken.stp.StpUtil) was not found on the classpath. Please add sa-token-spring-boot3-starter dependency or change ateng.boot.openapi.guard.mode.", e);
                }
            } else if (mode == GuardMode.BASIC && properties.isEnabled()) {
                OpenApiProperties.Basic basic = properties.getGuard().getBasic();
                if (basic == null || !StringUtils.hasText(basic.getUsername()) || !StringUtils.hasText(basic.getPassword())) {
                    throw new IllegalStateException("OpenAPI guard mode is set to BASIC, but ateng.boot.openapi.guard.basic.username or password is not configured.");
                }
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = extractNormalizedPath(request);

        // 1. 非 OpenAPI 文档与元数据路径，直接放行
        if (!isOpenApiPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 生产环境物理切断：当 enabled=false 时，直接响应 HTTP 404
        if (!properties.isEnabled()) {
            log.debug("OpenAPI document access blocked: enabled=false for path [{}]", path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Not Found");
            return;
        }

        GuardMode mode = properties.getGuard() != null ? properties.getGuard().getMode() : GuardMode.NONE;

        // 3. 免密模式：直接放行
        if (mode == GuardMode.NONE) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 凭证守卫模式下，静态资源（CSS, JS, WebJars, 图标等）白名单安全放行
        if (isStaticResource(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 5. HTTP Basic Auth 凭证守卫
        if (mode == GuardMode.BASIC) {
            if (handleBasicAuth(request, response)) {
                filterChain.doFilter(request, response);
            }
            return;
        }

        // 6. Sa-Token 会话凭证守卫
        if (mode == GuardMode.SATOKEN) {
            if (handleSaTokenAuth(request, response, path)) {
                filterChain.doFilter(request, response);
            }
        }
    }

    /**
     * 处理 HTTP Basic 认证逻辑
     */
    private boolean handleBasicAuth(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String authHeader = request.getHeader("Authorization");
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Basic ")) {
            sendBasicChallenge(response);
            return false;
        }

        try {
            String base64Credentials = authHeader.substring(6).trim();
            byte[] credBytes = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(credBytes, StandardCharsets.UTF_8);
            int colonIndex = credentials.indexOf(':');
            if (colonIndex == -1) {
                sendBasicChallenge(response);
                return false;
            }

            String username = credentials.substring(0, colonIndex);
            String password = credentials.substring(colonIndex + 1);

            OpenApiProperties.Basic basic = properties.getGuard().getBasic();
            String expectedUser = basic != null ? basic.getUsername() : null;
            String expectedPass = basic != null ? basic.getPassword() : null;

            if (expectedUser == null || expectedPass == null) {
                sendBasicChallenge(response);
                return false;
            }

            boolean userMatches = MessageDigest.isEqual(
                    username.getBytes(StandardCharsets.UTF_8),
                    expectedUser.getBytes(StandardCharsets.UTF_8));
            boolean passMatches = MessageDigest.isEqual(
                    password.getBytes(StandardCharsets.UTF_8),
                    expectedPass.getBytes(StandardCharsets.UTF_8));

            if (!userMatches || !passMatches) {
                log.warn("OpenAPI Basic authentication failed for username: [{}]", username);
                sendBasicChallenge(response);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("OpenAPI Basic authentication error: {}", e.getMessage());
            sendBasicChallenge(response);
            return false;
        }
    }

    /**
     * 处理 Sa-Token 鉴权逻辑
     */
    private boolean handleSaTokenAuth(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        boolean loggedIn = false;
        try {
            loggedIn = cn.dev33.satoken.stp.StpUtil.isLogin();
        } catch (Throwable t) {
            log.warn("Sa-Token authentication check failed: {}", t.getMessage());
        }

        if (!loggedIn) {
            log.warn("OpenAPI document access rejected: Sa-Token session not logged in for path [{}]", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("401 Unauthorized: OpenAPI document access requires Sa-Token authentication.");
            return false;
        }

        return true;
    }

    /**
     * 发送 HTTP 401 Basic 认证质询响应头
     */
    private void sendBasicChallenge(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", BASIC_REALM_HEADER);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("401 Unauthorized: OpenAPI document access requires Basic authentication.");
    }

    /**
     * 提取并规范化清洗请求路径
     */
    private String extractNormalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;

        if (path != null) {
            return path.replaceAll("/+", "/");
        }
        return "";
    }

    /**
     * 判定是否属于 OpenAPI 文档或元数据端点
     */
    public static boolean isOpenApiPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return path.equals("/docs")
                || path.startsWith("/docs/")
                || path.equals("/doc")
                || path.startsWith("/doc/")
                || path.equals("/doc.html")
                || path.equals("/swagger-ui.html")
                || path.equals("/swagger-ui")
                || path.startsWith("/swagger-ui/")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/");
    }

    /**
     * 判定是否属于静态资源白名单
     */
    public static boolean isStaticResource(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        if (path.startsWith("/webjars/")) {
            return true;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".png")
                || lower.endsWith(".ico")
                || lower.endsWith(".gif")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".woff")
                || lower.endsWith(".woff2")
                || lower.endsWith(".ttf")
                || lower.endsWith(".svg")
                || lower.endsWith(".map");
    }
}
