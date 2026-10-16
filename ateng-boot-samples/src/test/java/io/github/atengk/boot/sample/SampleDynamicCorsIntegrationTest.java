package io.github.atengk.boot.sample;

import io.github.atengk.boot.web.cors.CorsOriginProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样例工程动态 CORS 跨域安全端到端集成测试
 * 验证基于 Spring 原生 CorsConfigurationSource 的动态 Origin 反射、通配符域名匹配、凭证认证与非法来源阻断
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest(properties = {
        "ateng.boot.web.cors.enabled=true",
        "ateng.boot.web.cors.allowed-origins=https://*.ateng.com,https://partner.com",
        "ateng.boot.web.cors.allow-credentials=true"
})
@AutoConfigureMockMvc
@DisplayName("样例工程动态 CORS 跨域安全端到端集成测试")
class SampleDynamicCorsIntegrationTest {

    @Autowired
    private CorsOriginProvider corsOriginProvider;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("验证成功装配 CorsOriginProvider 实例")
    void shouldInjectCorsOriginProvider() {
        assertThat(corsOriginProvider).isNotNull();
        assertThat(corsOriginProvider.isAllowed("https://app.ateng.com")).isTrue();
        assertThat(corsOriginProvider.isAllowed("https://partner.com")).isTrue();
        assertThat(corsOriginProvider.isAllowed("http://localhost:5173")).isTrue();
    }

    @Test
    @DisplayName("端到端验证：通配符模式匹配来源预检 OPTIONS 动态反射 Origin 与凭据支持")
    void shouldAllowWildcardMatchedOriginWithCredentialsOnPreflight() throws Exception {
        String testOrigin = "https://app.ateng.com";

        mockMvc.perform(options("/api/users")
                        .header(HttpHeaders.ORIGIN, testOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, testOrigin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().exists(HttpHeaders.VARY));
    }

    @Test
    @DisplayName("端到端验证：真实跨域 GET 请求动态反射回写具体 Origin 标头")
    void shouldReflectSpecificOriginOnActualRequest() throws Exception {
        String testOrigin = "https://partner.com";

        mockMvc.perform(get("/api/users/page")
                        .header(HttpHeaders.ORIGIN, testOrigin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, testOrigin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("端到端验证：本地开发环境（localhost）快速放行")
    void shouldAllowLocalhostDevelopmentOrigin() throws Exception {
        String localhostOrigin = "http://localhost:5173";

        mockMvc.perform(options("/api/users")
                        .header(HttpHeaders.ORIGIN, localhostOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, localhostOrigin));
    }

    @Test
    @DisplayName("端到端验证：非法未授权来源预检请求被安全阻断")
    void shouldRejectUnauthorizedOrigin() throws Exception {
        String maliciousOrigin = "https://malicious-site.com";

        mockMvc.perform(options("/api/users")
                        .header(HttpHeaders.ORIGIN, maliciousOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
