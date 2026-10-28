package io.github.atengk.boot.openapi.guard;

import io.github.atengk.boot.openapi.properties.GuardMode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * OpenAPI 生产安全守卫过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("OpenApiGuardFilter 生产安全守卫过滤器单元测试")
class OpenApiGuardFilterTest {

    private OpenApiProperties properties;
    private OpenApiGuardFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        properties = new OpenApiProperties();
        filter = new OpenApiGuardFilter(properties);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("非 OpenAPI 业务端点（如 /api/users）直接放行")
    void testNonOpenApiPathPassThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("生产环境 enabled=false 物理切断：拦截所有文档与元数据并响应 404")
    void testPhysicalCutOffWhenDisabled() throws ServletException, IOException {
        properties.setEnabled(false);

        String[] testPaths = {
                "/swagger-ui/index.html",
                "/swagger-ui.html",
                "/docs",
                "/doc",
                "/doc.html",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/v3/api-docs/admin"
        };

        for (String path : testPaths) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus())
                    .as("Path [%s] should be blocked with 404 when disabled", path)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("多重斜杠路径混淆防卫（如 //swagger-ui//index.html）依然被精确拦截")
    void testPathNormalizationDefense() throws ServletException, IOException {
        properties.setEnabled(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "//swagger-ui//index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("静态资源白名单放行：CSS、JS、WebJars、图标在守卫模式下免密放行")
    void testStaticResourcePassThroughUnderGuardMode() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.BASIC);
        properties.getGuard().getBasic().setUsername("admin");
        properties.getGuard().getBasic().setPassword("secret");

        String[] staticPaths = {
                "/swagger-ui/swagger-ui.css",
                "/swagger-ui/swagger-ui-bundle.js",
                "/swagger-ui/favicon-32x32.png",
                "/webjars/swagger-ui/swagger-ui.css"
        };

        for (String path : staticPaths) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain, times(1)).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Basic Auth 守卫：未提供 Authorization 请求头时响应 401 并附带 WWW-Authenticate 响应头")
    void testBasicAuthChallengeWhenMissingCredentials() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.BASIC);
        properties.getGuard().getBasic().setUsername("admin");
        properties.getGuard().getBasic().setPassword("secret123");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(OpenApiGuardFilter.BASIC_REALM_HEADER);
        assertThat(response.getContentAsString()).contains("401 Unauthorized");
    }

    @Test
    @DisplayName("Basic Auth 守卫：提供错误用户名或密码时响应 401 质询")
    void testBasicAuthFailedWithWrongCredentials() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.BASIC);
        properties.getGuard().getBasic().setUsername("admin");
        properties.getGuard().getBasic().setPassword("secret123");

        String wrongCreds = Base64.getEncoder().encodeToString("admin:wrongpass".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        request.addHeader("Authorization", "Basic " + wrongCreds);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(OpenApiGuardFilter.BASIC_REALM_HEADER);
    }

    @Test
    @DisplayName("Basic Auth 守卫：提供正确凭据时成功放行")
    void testBasicAuthSuccessWithValidCredentials() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.BASIC);
        properties.getGuard().getBasic().setUsername("admin");
        properties.getGuard().getBasic().setPassword("secret123");

        String validCreds = Base64.getEncoder().encodeToString("admin:secret123".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        request.addHeader("Authorization", "Basic " + validCreds);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Sa-Token 守卫：未登录时拦截并响应 401")
    void testSaTokenAuthUnauthenticatedRejected() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.SATOKEN);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Sa-Token authentication");
    }

    @Test
    @DisplayName("Fail-Fast 防御：配置 guard.mode=BASIC 但未配置用户名或密码时启动抛出 IllegalStateException")
    void testBasicAuthFailFastWhenCredentialsNotConfigured() {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.BASIC);
        properties.getGuard().getBasic().setUsername(null);
        properties.getGuard().getBasic().setPassword(null);

        assertThatThrownBy(() -> filter.afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ateng.boot.openapi.guard.basic.username or password is not configured");
    }

    @Test
    @DisplayName("免密模式 GuardMode.NONE：文档和元数据端点直接放行")
    void testNoneModePassThrough() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.getGuard().setMode(GuardMode.NONE);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
