package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalStatus;
import io.github.atengk.boot.sample.ai.dto.OrderRefundTriggerRequest;
import io.github.atengk.boot.sample.ai.dto.ToolApprovalDecisionRequest;
import io.github.atengk.boot.sample.ai.tool.OrderRefundTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 声明式高危工具防卫与人机协作审批控制器端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 高危工具人机协作审批控制器端到端集成测试")
class ToolApprovalControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRefundTool orderRefundTool;

    @BeforeEach
    void setUp() {
        orderRefundTool.resetCounter();
    }

    @Test
    @DisplayName("高危工具拦截、生成待审批工单并审批重放成功闭环测试")
    void testTriggerRefundAndApproveLifecycle() throws Exception {
        // 1. 触发高危退款工具执行，验证沙箱自动拦截并生成审批工单
        OrderRefundTriggerRequest request = OrderRefundTriggerRequest.builder()
                .orderId("ORD_TEST_999")
                .amount(188.50)
                .reason("用户商品质量问题售后退款")
                .build();

        MvcResult triggerResult = mockMvc.perform(post("/sample/ai/approval/trigger-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.approvalRequired").value(true))
                .andExpect(jsonPath("$.data.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.data.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.approvalType").value("ORDER_REFUND"))
                .andReturn();

        // 验证真实工具业务逻辑尚未执行
        assertThat(orderRefundTool.getRefundExecutionCount()).isZero();

        String responseBody = triggerResult.getResponse().getContentAsString();
        String ticketId = objectMapper.readTree(responseBody).path("data").path("ticketId").asText();
        assertThat(ticketId).isNotBlank();

        // 2. 查询待审批工单列表
        mockMvc.perform(get("/sample/ai/approval/tickets/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.ticketId == '" + ticketId + "')].status").value("PENDING"));

        // 3. 查询单条工单详情快照
        mockMvc.perform(get("/sample/ai/approval/tickets/" + ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.ticketId").value(ticketId))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.approvalType").value("ORDER_REFUND"));

        // 4. 管理员审核通过并重放执行
        ToolApprovalDecisionRequest approveRequest = ToolApprovalDecisionRequest.builder()
                .comment("财务审核无误，准予退款")
                .build();

        mockMvc.perform(post("/sample/ai/approval/tickets/" + ticketId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.ticketId").value(ticketId))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.result").value("订单 [ORD_TEST_999] 退款成功，金额: ￥188.50，原因: 用户商品质量问题售后退款"));

        // 验证真实工具业务逻辑此时已成功重放执行
        assertThat(orderRefundTool.getRefundExecutionCount()).isEqualTo(1);

        // 5. 验证工单状态已跃迁为 APPROVED
        mockMvc.perform(get("/sample/ai/approval/tickets/" + ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewComment").value("财务审核无误，准予退款"));
    }

    @Test
    @DisplayName("高危工具拦截后被管理员人工驳回测试")
    void testTriggerRefundAndRejectLifecycle() throws Exception {
        OrderRefundTriggerRequest request = OrderRefundTriggerRequest.builder()
                .orderId("ORD_TEST_REJECT_888")
                .amount(999.00)
                .reason("恶意刷单嫌疑申请退款")
                .build();

        MvcResult triggerResult = mockMvc.perform(post("/sample/ai/approval/trigger-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketId").isNotEmpty())
                .andReturn();

        String ticketId = objectMapper.readTree(triggerResult.getResponse().getContentAsString())
                .path("data").path("ticketId").asText();

        // 驳回工单
        ToolApprovalDecisionRequest rejectRequest = ToolApprovalDecisionRequest.builder()
                .reason("疑似刷单行为，安全风控驳回")
                .build();

        mockMvc.perform(post("/sample/ai/approval/tickets/" + ticketId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.ticketId").value(ticketId))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.errorMessage").value("疑似刷单行为，安全风控驳回"));

        // 验证真实工具业务逻辑从未被执行
        assertThat(orderRefundTool.getRefundExecutionCount()).isZero();

        // 验证工单状态为 REJECTED
        mockMvc.perform(get("/sample/ai/approval/tickets/" + ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(ToolApprovalStatus.REJECTED.name()));
    }
}
