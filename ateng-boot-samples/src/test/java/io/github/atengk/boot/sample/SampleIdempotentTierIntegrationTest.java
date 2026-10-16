package io.github.atengk.boot.sample;

import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样例工程幂等存储引擎分层与自适应端到端集成测试
 * 验证在真实 Spring Boot 运行时环境中开箱即用的默认 Tier 3 单机内存引擎与切面协作
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("样例工程幂等存储引擎分层集成测试")
class SampleIdempotentTierIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private IdempotentKeyStore idempotentKeyStore;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("验证 samples 环境下默认注入 Tier 3 单机内存幂等存储引擎")
    void shouldInjectInMemoryKeyStoreByDefaultInSamples() {
        assertThat(idempotentKeyStore)
                .isNotNull()
                .isInstanceOf(InMemoryIdempotentKeyStore.class);
    }

    @Test
    @DisplayName("端到端验证：通过 MockMvc 验证幂等切面与存储引擎端到端协同阻断")
    void shouldCoordinateAspectWithKeyStoreEndToEnd() throws Exception {
        String testKey = "TIER_TEST_" + System.currentTimeMillis();
        String payload = """
                {
                    "orderNo": "%s",
                    "amount": 100.00
                }
                """.formatted(testKey);

        // 首次请求成功
        mockMvc.perform(post("/api/samples/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 并发拦截阻断
        mockMvc.perform(post("/api/samples/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003));
    }
}
