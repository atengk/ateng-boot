package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.AiRateLimitRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 微观突发滑动限流与配额防卫端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 微观突发滑动限流端到端集成测试")
class RateLimitControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("验证 POST /sample/ai/ratelimit/try-acquire 正常申请流量配额通过")
    void testTryAcquireSuccess() throws Exception {
        AiRateLimitRequest request = AiRateLimitRequest.builder()
                .apiKey("sk-test-sample-key")
                .tenantId("tenant-enterprise")
                .modelName("gpt-4o")
                .tokens(50)
                .build();

        mockMvc.perform(post("/sample/ai/ratelimit/try-acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowed").isBoolean())
                .andExpect(jsonPath("$.data.clientKey").value("tenant-enterprise"))
                .andExpect(jsonPath("$.data.message").isNotEmpty());
    }
}
