package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.ai.dto.OrderParallelStartRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并行扇出-汇聚认知状态图 (ParallelFanOutWorkflow) 端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("并行扇出-汇聚认知状态图 (ParallelFanOutWorkflow) 端到端集成测试")
class ParallelWorkflowControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("正常订单并发扇出风控、库存与授信三分支并汇聚核验放行，且快照可回查")
    void testParallelOrderVerificationSuccess() throws Exception {
        MDC.put("traceId", "trace-parallel-e2e-01");
        TenantContextHolder.setTenantId("tenant-vip-01");
        UserContextHolder.setUserId("user-888");

        OrderParallelStartRequest request = OrderParallelStartRequest.builder()
                .orderId("ORD_PAR_1001")
                .userId("U-888")
                .amount(new BigDecimal("1999.00"))
                .skuCode("SKU-IPHONE-16")
                .riskDelayMs(0L)
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/workflow/parallel/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.approved").value(true))
                .andExpect(jsonPath("$.data.state.branchResults.risk_audit").value("LOW_RISK"))
                .andExpect(jsonPath("$.data.state.branchResults.inventory_lock").value("STOCK_LOCKED:SKU-IPHONE-16"))
                .andExpect(jsonPath("$.data.state.branchResults.credit_quota").value("QUOTA_SUFFICIENT:U-888"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String threadId = root.path("data").path("threadId").asText();
        assertThat(threadId).isNotBlank();
        assertThat(root.path("data").path("state").path("finalOutput").asText())
                .contains("ORD_PAR_1001", "approved=true", "degradedCount=0");

        // 回查持久化快照接口
        mockMvc.perform(get("/sample/ai/workflow/parallel/snapshot/{threadId}", threadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.threadId").value(threadId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("单分支模拟超时在 BEST_EFFORT 策略下优雅降级并保留其余成功分支与错误明细")
    void testParallelBranchTimeoutBestEffortDegradation() throws Exception {
        OrderParallelStartRequest request = OrderParallelStartRequest.builder()
                .orderId("ORD_PAR_TIMEOUT_1002")
                .userId("U-999")
                .amount(new BigDecimal("3500.00"))
                .skuCode("SKU-MACBOOK")
                .riskDelayMs(900L)
                .build();

        mockMvc.perform(post("/sample/ai/workflow/parallel/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.approved").value(false))
                .andExpect(jsonPath("$.data.state.branchResults.inventory_lock").value("STOCK_LOCKED:SKU-MACBOOK"))
                .andExpect(jsonPath("$.data.state.branchResults.credit_quota").value("QUOTA_SUFFICIENT:U-999"))
                .andExpect(jsonPath("$.data.state.branchErrors.risk_audit.errorType").value("TIMEOUT"));
    }

    @Test
    @DisplayName("入参 JSR-303 校验失败时在接入层 Fail-Fast 返回 400")
    void testParallelValidationFailFast() throws Exception {
        OrderParallelStartRequest invalid = OrderParallelStartRequest.builder()
                .orderId("")
                .userId("u1")
                .amount(new BigDecimal("-5.00"))
                .skuCode("")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/parallel/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
