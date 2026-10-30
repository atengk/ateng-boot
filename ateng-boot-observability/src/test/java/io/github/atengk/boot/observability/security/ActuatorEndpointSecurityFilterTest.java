package io.github.atengk.boot.observability.security;

import io.github.atengk.boot.observability.event.ActuatorAccessDeniedEvent;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ActuatorEndpointSecurityFilter 端点安全守卫过滤器单元测试
 * 验证白名单放行、高危端点锁定、CIDR IP 白名单免密、Basic Auth 与 Token 互斥核验、防时序绕过及安全事件发布
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
    @DisplayName("第一层网络防护：CIDR IP 白名单免密放行验证 (RemoteAddr 与 X-Forwarded-For)")
    void testCidrIpWhitelistPassThrough() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("super-token");
        properties.getSecurity().setAllowedIpRanges(List.of("10.0.0.0/8", "192.168.1.0/24"));

        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        // 1. RemoteAddr 命中 10.0.0.0/8 白名单 -> 免密放行
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/actuator/env");
        req1.setRemoteAddr("10.5.6.7");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        FilterChain chain1 = mock(FilterChain.class);

        filter.doFilter(req1, res1, chain1);
        verify(chain1).doFilter(req1, res1);
        assertThat(res1.getStatus()).isEqualTo(200);

        // 2. X-Forwarded-For 命中 192.168.1.0/24 白名单 -> 免密放行
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/actuator/beans");
        req2.setRemoteAddr("203.0.113.1");
        req2.addHeader("X-Forwarded-For", "192.168.1.88, 10.0.0.1");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);

        filter.doFilter(req2, res2, chain2);
        verify(chain2).doFilter(req2, res2);
        assertThat(res2.getStatus()).isEqualTo(200);

        // 3. IP 未命中白名单且无凭据 -> 阻断 403
        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/actuator/env");
        req3.setRemoteAddr("203.0.113.50");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        FilterChain chain3 = mock(FilterChain.class);

        filter.doFilter(req3, res3, chain3);
        verify(chain3, never()).doFilter(req3, res3);
        assertThat(res3.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("第二层凭据防护：HTTP Basic Auth 认证成功放行与失败阻断")
    void testBasicAuthValidation() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().getBasicAuth().setEnabled(true);
        properties.getSecurity().getBasicAuth().setUsername("actuator-admin");
        properties.getSecurity().getBasicAuth().setPassword("Admin@2026");

        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        // 1. 正确 Basic Auth 账号密码 -> 放行
        String validAuth = "Basic " + Base64.getEncoder().encodeToString("actuator-admin:Admin@2026".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest reqValid = new MockHttpServletRequest("GET", "/actuator/env");
        reqValid.addHeader(ActuatorEndpointSecurityFilter.AUTHORIZATION_HEADER, validAuth);
        MockHttpServletResponse resValid = new MockHttpServletResponse();
        FilterChain chainValid = mock(FilterChain.class);

        filter.doFilter(reqValid, resValid, chainValid);
        verify(chainValid).doFilter(reqValid, resValid);
        assertThat(resValid.getStatus()).isEqualTo(200);

        // 2. 错误密码 -> 阻断 403
        String wrongAuth = "Basic " + Base64.getEncoder().encodeToString("actuator-admin:WrongPass".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest reqWrong = new MockHttpServletRequest("GET", "/actuator/env");
        reqWrong.addHeader(ActuatorEndpointSecurityFilter.AUTHORIZATION_HEADER, wrongAuth);
        MockHttpServletResponse resWrong = new MockHttpServletResponse();
        FilterChain chainWrong = mock(FilterChain.class);

        filter.doFilter(reqWrong, resWrong, chainWrong);
        verify(chainWrong, never()).doFilter(reqWrong, resWrong);
        assertThat(resWrong.getStatus()).isEqualTo(403);

        // 3. 非法畸形 Basic Auth Header -> 阻断 403
        MockHttpServletRequest reqMalformed = new MockHttpServletRequest("GET", "/actuator/env");
        reqMalformed.addHeader(ActuatorEndpointSecurityFilter.AUTHORIZATION_HEADER, "Basic ???not-valid-base64???");
        MockHttpServletResponse resMalformed = new MockHttpServletResponse();
        FilterChain chainMalformed = mock(FilterChain.class);

        filter.doFilter(reqMalformed, resMalformed, chainMalformed);
        verify(chainMalformed, never()).doFilter(reqMalformed, resMalformed);
        assertThat(resMalformed.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("双因子互斥凭据核验 (Token OR Basic Auth 双通道)")
    void testTokenAndBasicAuthOrLogic() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("custom-token-999");
        properties.getSecurity().getBasicAuth().setEnabled(true);
        properties.getSecurity().getBasicAuth().setUsername("admin");
        properties.getSecurity().getBasicAuth().setPassword("pwd123");

        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        // 1. 仅携带合法 Token -> 放行
        MockHttpServletRequest reqToken = new MockHttpServletRequest("GET", "/actuator/env");
        reqToken.addHeader(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "custom-token-999");
        MockHttpServletResponse resToken = new MockHttpServletResponse();
        FilterChain chainToken = mock(FilterChain.class);
        filter.doFilter(reqToken, resToken, chainToken);
        verify(chainToken).doFilter(reqToken, resToken);

        // 2. 仅携带合法 Basic Auth -> 放行
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:pwd123".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest reqBasic = new MockHttpServletRequest("GET", "/actuator/beans");
        reqBasic.addHeader(ActuatorEndpointSecurityFilter.AUTHORIZATION_HEADER, basic);
        MockHttpServletResponse resBasic = new MockHttpServletResponse();
        FilterChain chainBasic = mock(FilterChain.class);
        filter.doFilter(reqBasic, resBasic, chainBasic);
        verify(chainBasic).doFilter(reqBasic, resBasic);

        // 3. 既提供错误 Token 又提供错误 Basic -> 阻断 403
        MockHttpServletRequest reqBothWrong = new MockHttpServletRequest("GET", "/actuator/beans");
        reqBothWrong.addHeader(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "wrong-token");
        reqBothWrong.addHeader(ActuatorEndpointSecurityFilter.AUTHORIZATION_HEADER, "Basic " + Base64.getEncoder().encodeToString("admin:wrong".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse resBothWrong = new MockHttpServletResponse();
        FilterChain chainBothWrong = mock(FilterChain.class);
        filter.doFilter(reqBothWrong, resBothWrong, chainBothWrong);
        verify(chainBothWrong, never()).doFilter(reqBothWrong, resBothWrong);
        assertThat(resBothWrong.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("未授权阻断时自动发布 ActuatorAccessDeniedEvent 领域审计事件")
    void testAccessDeniedEventPublished() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("safe-token");

        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties, "/actuator", eventPublisher);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        request.setRemoteAddr("198.51.100.22");
        request.addHeader(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);

        ArgumentCaptor<ActuatorAccessDeniedEvent> captor = ArgumentCaptor.forClass(ActuatorAccessDeniedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ActuatorAccessDeniedEvent capturedEvent = captor.getValue();
        assertThat(capturedEvent.getUri()).isEqualTo("/actuator/env");
        assertThat(capturedEvent.getClientIp()).isEqualTo("198.51.100.22");
        assertThat(capturedEvent.getReason()).contains("X-Actuator-Token 凭据缺失或不匹配");
        assertThat(capturedEvent.toString()).contains("/actuator/env");
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

    @Test
    @DisplayName("前缀相似的常规业务端点（如 /actuators, /actuator-ui）不被误判为 Actuator 并正常放行")
    void testSimilarPrefixEndpointNotBlocked() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        properties.getSecurity().setToken("strict-token");
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        String[] businessUris = {"/actuators", "/actuator-ui", "/actuator-monitor/metrics"};
        for (String uri : businessUris) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("服务端 Basic Auth 用户名密码未配置或为空时，空凭据无法逃逸放行并阻断 403")
    void testBasicAuthEmptyConfiguredCredentialsRejectsEmptyInput() throws ServletException, IOException {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getSecurity().setLockdownEnabled(true);
        // 开启了 basic-auth 但未配置有效的 username 和 password
        properties.getSecurity().getBasicAuth().setEnabled(true);
        properties.getSecurity().getBasicAuth().setUsername("");
        properties.getSecurity().getBasicAuth().setPassword("");
        ActuatorEndpointSecurityFilter filter = new ActuatorEndpointSecurityFilter(properties);

        // 构造空凭据 Basic Og== (即解码为 ":")
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        request.addHeader("Authorization", "Basic Og==");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
