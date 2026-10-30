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
    private volatile OpenApiCidrMatcher cidrMatcher;

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
            } else if (mode == GuardMode.TOKEN && properties.isEnabled()) {
                OpenApiProperties.Token token = properties.getGuard().getToken();
                if (token == null || !StringUtils.hasText(token.getSecret())) {
                    throw new IllegalStateException("OpenAPI guard mode is set to TOKEN, but ateng.boot.openapi.guard.token.secret is not configured.");
                }
            } else if (mode == GuardMode.IP_WHITELIST && properties.isEnabled()) {
                OpenApiProperties.IpWhitelist ipWhitelist = properties.getGuard().getIpWhitelist();
                if (ipWhitelist == null || ipWhitelist.getCidrs().isEmpty()) {
                    throw new IllegalStateException("OpenAPI guard mode is set to IP_WHITELIST, but ateng.boot.openapi.guard.ip-whitelist.cidrs is empty.");
                }
            }

            // 预初始化 CIDR 匹配器
            OpenApiProperties.IpWhitelist ipWhitelist = properties.getGuard().getIpWhitelist();
            if (ipWhitelist != null && !ipWhitelist.getCidrs().isEmpty()) {
                this.cidrMatcher = new OpenApiCidrMatcher(ipWhitelist.getCidrs());
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

        // 3. 静态资源（CSS, JS, WebJars, 图标等）白名单安全放行，防御样式死锁
        if (isStaticResource(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        OpenApiProperties.Guard guard = properties.getGuard() != null ? properties.getGuard() : new OpenApiProperties.Guard();
        GuardMode mode = guard.getMode();
        OpenApiProperties.IpWhitelist ipWhitelist = guard.getIpWhitelist();

        // 4. 正交 CIDR / IP 白名单网络防火墙拦截
        boolean ipWhitelistEnabled = (ipWhitelist != null && ipWhitelist.isEnabled()) || (mode == GuardMode.IP_WHITELIST);
        if (ipWhitelistEnabled) {
            String clientIp = extractClientIp(request);
            OpenApiCidrMatcher matcher = getOrCreateCidrMatcher();
            if (matcher == null || !matcher.matches(clientIp)) {
                log.warn("OpenAPI document access rejected: client IP [{}] not in whitelist for path [{}]", clientIp, path);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("403 Forbidden: OpenAPI document access rejected by IP whitelist.");
                return;
            }
        }

        // 5. 纯 IP 白名单模式：白名单校验通过即直接放行
        if (mode == GuardMode.IP_WHITELIST) {
            filterChain.doFilter(request, response);
            return;
        }

        // 6. 免密模式：直接放行
        if (mode == GuardMode.NONE) {
            filterChain.doFilter(request, response);
            return;
        }

        // 7. HTTP Basic Auth 凭证守卫
        if (mode == GuardMode.BASIC) {
            if (handleBasicAuth(request, response)) {
                filterChain.doFilter(request, response);
            }
            return;
        }

        // 8. Sa-Token 会话凭证守卫
        if (mode == GuardMode.SATOKEN) {
            if (handleSaTokenAuth(request, response, path)) {
                filterChain.doFilter(request, response);
            }
            return;
        }

        // 9. 静态令牌标头守卫
        if (mode == GuardMode.TOKEN) {
            if (handleTokenAuth(request, response, path)) {
                filterChain.doFilter(request, response);
            }
        }
    }

    /**
     * 获取或延迟构建 CIDR 匹配器
     */
    private OpenApiCidrMatcher getOrCreateCidrMatcher() {
        if (cidrMatcher == null && properties.getGuard() != null) {
            OpenApiProperties.IpWhitelist ipWhitelist = properties.getGuard().getIpWhitelist();
            if (ipWhitelist != null && !ipWhitelist.getCidrs().isEmpty()) {
                cidrMatcher = new OpenApiCidrMatcher(ipWhitelist.getCidrs());
            }
        }
        return cidrMatcher;
    }

    /**
     * 提取客户端真实 IP 地址
     * <p>
     * 若配置了受信任代理标头（{@code trusted-header}，如 {@code X-Forwarded-For}），则从该标头提取首个非空有效 IP；
     * 若未配置受信任标头，严格读取 {@link HttpServletRequest#getRemoteAddr()}，杜绝客户端伪造 Header 越权。
     * </p>
     *
     * @param request HTTP 请求
     * @return 客户端 IP 地址
     */
    public String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        OpenApiProperties.IpWhitelist ipWhitelist = (properties.getGuard() != null)
                ? properties.getGuard().getIpWhitelist()
                : null;
        String trustedHeader = (ipWhitelist != null) ? ipWhitelist.getTrustedHeader() : null;

        if (StringUtils.hasText(trustedHeader)) {
            String headerValue = request.getHeader(trustedHeader.trim());
            if (StringUtils.hasText(headerValue)) {
                String[] parts = headerValue.split(",");
                for (String part : parts) {
                    String candidate = part.trim();
                    if (!candidate.isBlank() && !"unknown".equalsIgnoreCase(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr.trim() : "";
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
     * 处理静态令牌认证逻辑
     */
    private boolean handleTokenAuth(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        OpenApiProperties.Token tokenProp = (properties.getGuard() != null)
                ? properties.getGuard().getToken()
                : null;

        String expectedSecret = (tokenProp != null) ? tokenProp.getSecret() : null;
        if (!StringUtils.hasText(expectedSecret)) {
            log.warn("OpenAPI Token authentication rejected: secret is not configured.");
            sendTokenChallenge(response);
            return false;
        }

        String headerName = (tokenProp != null) ? tokenProp.getHeaderName() : "X-Doc-Token";
        String providedToken = request.getHeader(headerName);

        // 如果 Header 缺失且允许 Query 参数，则尝试从 Query 参数提取
        if (!StringUtils.hasText(providedToken) && tokenProp != null && tokenProp.isAllowQueryParam()) {
            providedToken = request.getParameter(tokenProp.getQueryParamName());
        }

        if (!StringUtils.hasText(providedToken)) {
            log.warn("OpenAPI Token authentication rejected: missing token in header [{}] or query for path [{}]", headerName, path);
            sendTokenChallenge(response);
            return false;
        }

        boolean matches = MessageDigest.isEqual(
                providedToken.trim().getBytes(StandardCharsets.UTF_8),
                expectedSecret.trim().getBytes(StandardCharsets.UTF_8)
        );

        if (!matches) {
            log.warn("OpenAPI Token authentication failed for path [{}]", path);
            sendTokenChallenge(response);
            return false;
        }

        return true;
    }

    /**
     * 发送 HTTP 401 Token 认证失败响应
     */
    private void sendTokenChallenge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("401 Unauthorized: OpenAPI document access requires valid Token.");
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
