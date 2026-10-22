package io.github.atengk.boot.observability.security;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ActuatorEndpointSecurityFilter 端点安全守卫过滤器单元测试
 * 验证白名单放行、高危端点锁定、Token 凭据核验及防时序绕过
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("ActuatorEndpointSecurityFilter 端点安全过滤器单元测试")
class ActuatorEndpointSecurityFilterTest {

    @Test
    @DisplayName("非 Actuator 业务端点（如 /api/order/1）无条件直接放行")
    void testNonActuatorEndpointPassThrough() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/order/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Actuator 白名单端点（/health, /info, /prometheus, /pre-stop）免密无条件放行")
    void testWhitelistedEndpointsPassThrough() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("super-secret");
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        String[] safeUris = {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/health/storage",
                "/actuator/info",
                "/actuator/prometheus",
                "/actuator/pre-stop"
        };

        for (String uri : safeUris) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("加固模式下访问高危端点（/actuator/env），若未配置授权 Token 则全面阻断 403")
    void testSensitiveEndpointBlockedWhenNoTokenConfigured() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken(""); // 未配置 Token
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":403");
        assertThat(response.getContentAsString()).contains("未配置授权 Token");
    }

    @Test
    @DisplayName("加固模式下访问高危端点（/actuator/beans），凭证缺失或错误时阻断 403，凭证匹配时放行")
    void testSensitiveEndpointTokenValidation() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("admin-secret-token");
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        // 1. 无 Token
        MockHttpServletRequest requestNoToken = new MockHttpServletRequest("GET", "/actuator/beans");
        MockHttpServletResponse responseNoToken = new MockHttpServletResponse();
        FilterChain chainNoToken = mock(FilterChain.class);

        filter.doFilter(requestNoToken, responseNoToken, chainNoToken);
        verify(chainNoToken, never()).doFilter(requestNoToken, responseNoToken);
        assertThat(responseNoToken.getStatus()).isEqualTo(403);

        // 2. 错误 Token
        MockHttpServletRequest requestWrongToken = new MockHttpServletRequest("GET", "/actuator/beans");
        requestWrongToken.addHeader(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse responseWrongToken = new MockHttpServletResponse();
        FilterChain chainWrongToken = mock(FilterChain.class);

        filter.doFilter(requestWrongToken, responseWrongToken, chainWrongToken);
        verify(chainWrongToken, never()).doFilter(requestWrongToken, responseWrongToken);
        assertThat(responseWrongToken.getStatus()).isEqualTo(403);

        // 3. 正确 Token
        MockHttpServletRequest requestValidToken = new MockHttpServletRequest("GET", "/actuator/beans");
        requestValidToken.addHeader(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "admin-secret-token");
        MockHttpServletResponse responseValidToken = new MockHttpServletResponse();
        FilterChain chainValidToken = mock(FilterChain.class);

        filter.doFilter(requestValidToken, responseValidToken, chainValidToken);
        verify(chainValidToken).doFilter(requestValidToken, responseValidToken);
    }

    @Test
    @DisplayName("防御双斜杠路径混淆绕过攻击（如 /actuator//env）")
    void testDoubleSlashBypassDefense() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("correct-secret");
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator//env");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("当 lockdown-enabled=false 关闭安全加固时，敏感端点无需 Token 放行")
    void testLockdownDisabledPassThrough() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(false);
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("自定义基础路径（如 /management）兼容性测试")
    void testCustomBasePath() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("custom-mgmt-token");
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties, "/management");

        // 白名单放行
        MockHttpServletRequest reqHealth = new MockHttpServletRequest("GET", "/management/health");
        MockHttpServletResponse resHealth = new MockHttpServletResponse();
        FilterChain chainHealth = mock(FilterChain.class);
        filter.doFilter(reqHealth, resHealth, chainHealth);
        verify(chainHealth).doFilter(reqHealth, resHealth);

        // 高危敏感阻断
        MockHttpServletRequest reqEnv = new MockHttpServletRequest("GET", "/management/env");
        MockHttpServletResponse resEnv = new MockHttpServletResponse();
        FilterChain chainEnv = mock(FilterChain.class);
        filter.doFilter(reqEnv, resEnv, chainEnv);
        verify(chainEnv, never()).doFilter(reqEnv, resEnv);
        assertThat(resEnv.getStatus()).isEqualTo(403);
    }
}
