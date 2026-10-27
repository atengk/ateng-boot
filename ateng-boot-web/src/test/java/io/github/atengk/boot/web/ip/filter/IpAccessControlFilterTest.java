package io.github.atengk.boot.web.ip.filter;

import io.github.atengk.boot.web.ip.IpAccessMode;
import io.github.atengk.boot.web.ip.properties.IpAccessControlProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * URL 路径级 IP 访问控制过滤器单测
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("URL 路径级 IP 访问控制过滤器单测")
class IpAccessControlFilterTest {

    private IpAccessControlProperties properties;
    private IpAccessControlFilter filter;
    private FilterChain filterChain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        properties = new IpAccessControlProperties();
        properties.setEnabled(true);
        properties.getFilter().setEnabled(true);
        filter = new IpAccessControlFilter(properties);
        filterChain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("当全局或过滤器未启用时直接放行")
    void shouldPassWhenDisabled() throws ServletException, IOException {
        properties.setEnabled(false);
        request.setRequestURI("/admin/users");
        request.setRemoteAddr("10.0.0.1");

        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);

        properties.setEnabled(true);
        properties.getFilter().setEnabled(false);
        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(2)).doFilter(request, response);
    }

    @Test
    @DisplayName("命中的排除白名单 URL (excludeUrls) 直接放行")
    void shouldPassWhenMatchingExcludeUrls() throws ServletException, IOException {
        properties.getFilter().setMode(IpAccessMode.WHITE_LIST);
        properties.getFilter().setRules(List.of("192.168.1.0/24"));
        properties.getFilter().setExcludeUrls(List.of("/admin/health", "/admin/public/**"));

        request.setRequestURI("/admin/health");
        request.setRemoteAddr("10.0.0.1"); // 非白名单 IP

        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);

        request.setRequestURI("/admin/public/info");
        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(2)).doFilter(request, response);
    }

    @Test
    @DisplayName("未匹配拦截 URL (urlPatterns) 的请求直接放行")
    void shouldPassWhenNotMatchingUrlPatterns() throws ServletException, IOException {
        properties.getFilter().setMode(IpAccessMode.WHITE_LIST);
        properties.getFilter().setRules(List.of("192.168.1.0/24"));
        properties.getFilter().setUrlPatterns(List.of("/admin/**"));

        request.setRequestURI("/api/v1/goods");
        request.setRemoteAddr("10.0.0.1");

        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("白名单模式：白名单内 IP 放行，未授权 IP 优雅拦截 (HTTP 200 + Result 403 + Header)")
    void shouldHandleWhiteListModeDefault() throws ServletException, IOException {
        properties.getFilter().setMode(IpAccessMode.WHITE_LIST);
        properties.getFilter().setRules(List.of("192.168.1.0/24", "127.0.0.1"));
        properties.getFilter().setUrlPatterns(List.of("/admin/**"));

        // 1. 白名单放行
        request.setRequestURI("/admin/users");
        request.setRemoteAddr("192.168.1.100");
        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);

        // 2. 非白名单拦截
        MockHttpServletRequest blockedRequest = new MockHttpServletRequest("GET", "/admin/users");
        blockedRequest.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();

        filter.doFilter(blockedRequest, blockedResponse, filterChain);
        verify(filterChain, never()).doFilter(blockedRequest, blockedResponse); // 确认未放行

        assertThat(blockedResponse.getStatus()).isEqualTo(200);
        assertThat(blockedResponse.getHeader(IpAccessControlFilter.DENIED_HEADER)).isEqualTo(IpAccessControlFilter.DENIED_REASON);
        assertThat(blockedResponse.getContentAsString()).contains("\"code\":403");
        assertThat(blockedResponse.getContentAsString()).contains("IP 访问受限，拒绝访问");
    }

    @Test
    @DisplayName("物理切断模式：开启 http-status-403 时返回 HTTP 403 状态码")
    void shouldHandleHttpStatus403Mode() throws ServletException, IOException {
        properties.setHttpStatus403(true);
        properties.getFilter().setMode(IpAccessMode.WHITE_LIST);
        properties.getFilter().setRules(List.of("192.168.1.0/24"));

        request.setRequestURI("/admin/users");
        request.setRemoteAddr("10.0.0.5");

        filter.doFilter(request, response, filterChain);
        verify(filterChain, never()).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(IpAccessControlFilter.DENIED_HEADER)).isEqualTo(IpAccessControlFilter.DENIED_REASON);
        assertThat(response.getContentAsString()).contains("\"code\":403");
    }

    @Test
    @DisplayName("黑名单模式：黑名单内 IP 阻断，其他放行")
    void shouldHandleBlackListMode() throws ServletException, IOException {
        properties.getFilter().setMode(IpAccessMode.BLACK_LIST);
        properties.getFilter().setRules(List.of("10.0.0.0/8", "203.0.113.50"));

        // 黑名单拦截
        request.setRequestURI("/api/submit");
        request.setRemoteAddr("10.1.2.3");
        filter.doFilter(request, response, filterChain);
        verify(filterChain, never()).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(IpAccessControlFilter.DENIED_HEADER)).isEqualTo(IpAccessControlFilter.DENIED_REASON);
        assertThat(response.getContentAsString()).contains("\"code\":403");

        // 正常 IP 放行
        MockHttpServletRequest allowedRequest = new MockHttpServletRequest("GET", "/api/submit");
        allowedRequest.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowedRequest, allowedResponse, filterChain);
        verify(filterChain, times(1)).doFilter(allowedRequest, allowedResponse);
    }

    @Test
    @DisplayName("IPv6 CIDR 子网支持测试")
    void shouldSupportIpv6Cidr() throws ServletException, IOException {
        properties.getFilter().setMode(IpAccessMode.WHITE_LIST);
        properties.getFilter().setRules(List.of("2001:db8::/32"));

        request.setRequestURI("/api/ipv6");
        request.setRemoteAddr("2001:db8:85a3::1");

        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);
    }
}
