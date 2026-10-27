package io.github.atengk.boot.web.filter;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 安全响应头防御与容器指纹清洗过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("HTTP 安全响应头防御与容器指纹清洗过滤器单元测试")
class SecurityHeadersFilterTest {

    @Test
    @DisplayName("1. 开箱即用注入 OWASP ASVS 与等保三级推荐基线标头")
    void shouldInjectDefaultSecurityHeaders() throws Exception {
        WebProperties.SecurityHeadersProperties properties = new WebProperties.SecurityHeadersProperties();
        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff");
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_FRAME_OPTIONS)).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_XSS_PROTECTION)).isEqualTo("1; mode=block");
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_REFERRER_POLICY)).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_CONTENT_SECURITY_POLICY)).isNull();
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_PERMISSIONS_POLICY)).isNull();
    }

    @Test
    @DisplayName("2. containsHeader 守卫防御：上游已存在安全标头时不覆盖")
    void shouldPreserveExistingUpstreamHeaders() throws Exception {
        WebProperties.SecurityHeadersProperties properties = new WebProperties.SecurityHeadersProperties();
        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 上游前置已设置
        response.setHeader(SecurityHeadersFilter.HEADER_X_FRAME_OPTIONS, "ALLOWALL");
        response.setHeader(SecurityHeadersFilter.HEADER_REFERRER_POLICY, "no-referrer");

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_FRAME_OPTIONS)).isEqualTo("ALLOWALL");
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_REFERRER_POLICY)).isEqualTo("no-referrer");
        // 未设置的仍自动补齐
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("3. 下游 Controller 或后续业务设置自定义标头时正常生效覆写")
    void shouldAllowDownstreamToOverwriteHeaders() throws Exception {
        WebProperties.SecurityHeadersProperties properties = new WebProperties.SecurityHeadersProperties();
        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        jakarta.servlet.FilterChain chain = (req, resp) -> {
            // 下游控制器覆写
            ((jakarta.servlet.http.HttpServletResponse) resp).setHeader(SecurityHeadersFilter.HEADER_X_FRAME_OPTIONS, "DENY");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_FRAME_OPTIONS)).isEqualTo("DENY");
    }

    @Test
    @DisplayName("4. 擦除与拦截容器 Server 响应头版本指纹")
    void shouldStripOrSuppressServerHeader() throws Exception {
        WebProperties.SecurityHeadersProperties properties = new WebProperties.SecurityHeadersProperties();
        properties.setRemoveServerHeader(true);
        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        jakarta.servlet.FilterChain chain = (req, resp) -> {
            jakarta.servlet.http.HttpServletResponse httpResp = (jakarta.servlet.http.HttpServletResponse) resp;
            // 模拟容器或后续组件试图设置 Server 响应头
            httpResp.setHeader("Server", "Apache-Coyote/1.1");
            httpResp.addHeader("Server", "Tomcat-10");
            assertThat(httpResp.getHeader("Server")).isNull();
            assertThat(httpResp.containsHeader("Server")).isFalse();
            assertThat(httpResp.getHeaders("Server")).isEmpty();
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Server")).isNull();
        assertThat(response.containsHeader("Server")).isFalse();
    }

    @Test
    @DisplayName("5. 显式配置 CSP 与 Permissions-Policy 时正确注入")
    void shouldInjectCspAndPermissionsPolicyWhenConfigured() throws Exception {
        WebProperties.SecurityHeadersProperties properties = new WebProperties.SecurityHeadersProperties();
        properties.setContentSecurityPolicy("default-src 'self'; script-src 'self' 'unsafe-inline'");
        properties.setPermissionsPolicy("camera=(), microphone=(), geolocation=()");

        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_CONTENT_SECURITY_POLICY))
                .isEqualTo("default-src 'self'; script-src 'self' 'unsafe-inline'");
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_PERMISSIONS_POLICY))
                .isEqualTo("camera=(), microphone=(), geolocation=()");
    }

    @Test
    @DisplayName("6. 全局禁用或单项禁用时跳过注入")
    void shouldSkipWhenDisabled() throws Exception {
        WebProperties.SecurityHeadersProperties properties = new WebProperties.SecurityHeadersProperties();
        properties.setEnabled(false);

        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_CONTENT_TYPE_OPTIONS)).isNull();
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_FRAME_OPTIONS)).isNull();
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_X_XSS_PROTECTION)).isNull();
        assertThat(response.getHeader(SecurityHeadersFilter.HEADER_REFERRER_POLICY)).isNull();
    }
}
