package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多租户原生限流端到端集成测试
 * 验证 LimitType.TENANT 原生租户流量隔离与超限熔断
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 多租户原生限流防刷端到端测试")
class WebTenantRateLimiterSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("多租户限流隔离与超限拦截：租户隔离独立计数，超限返回 429 业务错误与 Retry-After 标头")
    void shouldEnforceTenantRateLimitingAndIsolate() throws Exception {
        // 1. 租户 tenant_A 连续请求 2 次均成功放行
        TenantContextHolder.setTenantId("tenant_A");
        mockMvc.perform(get("/api/sample/ratelimit/tenant/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant=tenant_A"));

        mockMvc.perform(get("/api/sample/ratelimit/tenant/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant=tenant_A"));

        // 租户 tenant_A 第 3 次触发超限熔断（HTTP 200，业务码 429）
        mockMvc.perform(get("/api/sample/ratelimit/tenant/data"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("当前租户请求过于频繁，请稍后再试")));

        // 2. 切换至租户 tenant_B，验证流量完全隔离，不受 tenant_A 超限影响
        TenantContextHolder.setTenantId("tenant_B");
        mockMvc.perform(get("/api/sample/ratelimit/tenant/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant=tenant_B"));
    }
}
