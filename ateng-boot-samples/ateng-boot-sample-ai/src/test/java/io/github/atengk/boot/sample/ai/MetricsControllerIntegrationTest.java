package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.AiQuotaTestRequestDto;
import io.github.atengk.boot.sample.ai.dto.AiStreamFallbackRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 可观测度量、配额风控与保底估算控制器集成测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 度量监控、配额自验与保底估算控制器集成测试")
class MetricsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("测试指标大盘探查端点 GET /sample/ai/metrics/summary")
    void testMetricsSummaryEndpoint() throws Exception {
        mockMvc.perform(get("/sample/ai/metrics/summary")
                        .param("tenantId", "tenant-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalRequests").isNumber())
                .andExpect(jsonPath("$.data.totalTokens").isNumber())
                .andExpect(jsonPath("$.data.tenantDailyRequests").isNotEmpty())
                .andExpect(jsonPath("$.data.tenantDailyTokens").isNotEmpty());
    }

    @Test
    @DisplayName("测试流式保底估算体验端点 POST /sample/ai/metrics/stream-fallback")
    void testStreamFallbackEndpoint() throws Exception {
        AiStreamFallbackRequestDto request = new AiStreamFallbackRequestDto();
        request.setPrompt("你好世界"); // 4 汉字 -> 6 tokens
        request.setResponseText("Hello world"); // 2 英文词 -> 3 tokens

        mockMvc.perform(post("/sample/ai/metrics/stream-fallback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prompt").value("你好世界"))
                .andExpect(jsonPath("$.data.promptTokens").value(6))
                .andExpect(jsonPath("$.data.completionTokens").value(3))
                .andExpect(jsonPath("$.data.totalTokens").value(9));
    }

    @Test
    @DisplayName("测试配额风控自验端点 POST /sample/ai/metrics/quota/test")
    void testQuotaTestEndpoint() throws Exception {
        AiQuotaTestRequestDto request = new AiQuotaTestRequestDto();
        request.setTenantId("tenant-sample-e2e");
        request.setPromptTokens(15);
        request.setTotalTokens(45);
        request.setCheckOnly(false);

        mockMvc.perform(post("/sample/ai/metrics/quota/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PASS"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-sample-e2e"))
                .andExpect(jsonPath("$.data.currentDailyRequests").isNotEmpty())
                .andExpect(jsonPath("$.data.currentDailyTokens").isNotEmpty());
    }
}
