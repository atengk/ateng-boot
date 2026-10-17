package io.github.atengk.boot.sample;

import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样例工程限流防刷引擎分层与自适应端到端集成测试
 * 验证在真实 Spring Boot 运行时环境中开箱即用的 Tier 3 内存引擎、AOP 切面、Retry-After 响应头与多维度限流隔离
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("样例工程限流防刷引擎分层集成测试")
class SampleRateLimiterIntegrationTest {

    @Autowired
    private RateLimiterStore rateLimiterStore;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("验证 samples 环境下默认注入 Tier 3 单机内存令牌桶存储引擎")
    void shouldInjectInMemoryRateLimiterStoreByDefaultInSamples() {
        assertThat(rateLimiterStore)
                .isNotNull()
                .isInstanceOf(InMemoryRateLimiterStore.class);
    }

    @Test
    @DisplayName("端到端验证：默认方法级限流拦截与 Retry-After 响应头回写")
    void shouldEnforceDefaultRateLimitAndReturnRetryAfterHeader() throws Exception {
        // 第 1 次请求成功
        mockMvc.perform(get("/api/samples/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 2 次请求成功（达到上限 2 次）
        mockMvc.perform(get("/api/samples/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次请求超限拦截（返回 HTTP 200 + code: 429 与 Retry-After: 10 响应头）
        mockMvc.perform(get("/api/samples/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("默认接口限流拦截"))
                .andExpect(header().string("Retry-After", "10"));
    }

    @Test
    @DisplayName("端到端验证：按客户端 IP 维度限流与不同 IP 隔离")
    void shouldEnforceIpRateLimitAndDifferentiateDifferentIps() throws Exception {
        String ipA = "192.168.10.1";
        String ipB = "192.168.10.2";

        // IP A 首次请求
        mockMvc.perform(get("/api/samples/ratelimit/ip").header("X-Forwarded-For", ipA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // IP A 第二次请求
        mockMvc.perform(get("/api/samples/ratelimit/ip").header("X-Forwarded-For", ipA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // IP A 第三次请求拦截
        mockMvc.perform(get("/api/samples/ratelimit/ip").header("X-Forwarded-For", ipA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("IP 限流拦截"));

        // IP B 发起请求，不受 IP A 限流影响
        mockMvc.perform(get("/api/samples/ratelimit/ip").header("X-Forwarded-For", ipB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("端到端验证：SpEL 业务键与维度复合限流隔离")
    void shouldEnforceCompositeSpelRateLimit() throws Exception {
        String testIp = "10.0.0.99";

        // 租户 1001 两次请求放行
        mockMvc.perform(get("/api/samples/ratelimit/composite")
                        .header("X-Forwarded-For", testIp)
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/samples/ratelimit/composite")
                        .header("X-Forwarded-For", testIp)
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 租户 1001 第三次请求拦截
        mockMvc.perform(get("/api/samples/ratelimit/composite")
                        .header("X-Forwarded-For", testIp)
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("复合租户限流拦截"));

        // 租户 1002 正常放行
        mockMvc.perform(get("/api/samples/ratelimit/composite")
                        .header("X-Forwarded-For", testIp)
                        .param("tenantId", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
