package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.OrderPipelineCancelRequest;
import io.github.atengk.boot.sample.ai.dto.OrderPipelineResumeRequest;
import io.github.atengk.boot.sample.ai.dto.OrderPipelineStartRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 确定性顺序流水线认知工作流 (SequentialPipelineWorkflow) 端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("确定性顺序流水线多阶段串行与人机协同集成测试 (100% 离线自洽驱动)")
class AiSequentialWorkflowControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("端到端验证标准低风险实物订单全流程自动化执行至终态 COMPLETED")
    void testStandardLowRiskOrderFulfillment() throws Exception {
        OrderPipelineStartRequest request = OrderPipelineStartRequest.builder()
                .orderId("ORD_STD_001")
                .amount(new BigDecimal("299.00"))
                .itemType("PHYSICAL")
                .creditScore(780)
                .vipBypass(false)
                .remarks("普通用户标准实物订单快速放行")
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/workflow/pipeline/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.verified").value(true))
                .andExpect(jsonPath("$.data.state.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.state.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.state.deliveryStatus").value("DISPATCHED"))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String trackingNumber = jsonNode.path("data").path("state").path("trackingNumber").asText();
        assertThat(trackingNumber).startsWith("SF-EXPR-");
    }

    @Test
    @DisplayName("端到端验证 VIP 特权用户条件跳过风控评级阶段并顺利完成虚拟发货")
    void testVipBypassOrderFulfillment() throws Exception {
        OrderPipelineStartRequest request = OrderPipelineStartRequest.builder()
                .orderId("ORD_VIP_002")
                .amount(new BigDecimal("8888.00"))
                .itemType("VIRTUAL")
                .creditScore(820)
                .vipBypass(true)
                .remarks("VIP 会员尊享极速通道")
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/workflow/pipeline/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.verified").value(true))
                .andExpect(jsonPath("$.data.state.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.state.deliveryStatus").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.state.skippedStages[0]").value("risk_evaluation"))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String trackingNumber = jsonNode.path("data").path("state").path("trackingNumber").asText();
        assertThat(trackingNumber).startsWith("VIRT-KEY-");
    }

    @Test
    @DisplayName("端到端验证大额高危订单自动挂起 (SUSPENDED) 且人工审批 APPROVED 顺利推进至完成")
    void testHighRiskOrderHitlApprovalAndResume() throws Exception {
        OrderPipelineStartRequest startRequest = OrderPipelineStartRequest.builder()
                .orderId("ORD_HIGH_RISK_003")
                .amount(new BigDecimal("12888.00"))
                .itemType("PHYSICAL")
                .creditScore(560)
                .vipBypass(false)
                .remarks("超大额且低信用订单触发人机协同审核")
                .build();

        // 1. 启动流水线，预期挂起在 manual_audit
        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/pipeline/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.nextNode").value("manual_audit"))
                .andExpect(jsonPath("$.data.state.riskLevel").value("HIGH"))
                .andReturn();

        JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String threadId = startJson.path("data").path("threadId").asText();
        assertThat(threadId).isNotBlank();

        // 2. 验证快照查询端点
        mockMvc.perform(get("/sample/ai/workflow/pipeline/snapshot/{threadId}", threadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.threadId").value(threadId))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.nextNode").value("manual_audit"));

        // 3. 注入 APPROVED 决断恢复流转
        OrderPipelineResumeRequest resumeRequest = OrderPipelineResumeRequest.builder()
                .threadId(threadId)
                .action("APPROVED")
                .operator("auditor_chen")
                .comment("经核对交易凭据与电话确认，审核放行")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/pipeline/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.auditDecision").value("APPROVED"))
                .andExpect(jsonPath("$.data.state.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.state.deliveryStatus").value("DISPATCHED"));
    }

    @Test
    @DisplayName("端到端验证人工审核注入 REJECTED 决断时安全熔断至 FAILED 终态")
    void testHighRiskOrderHitlRejection() throws Exception {
        OrderPipelineStartRequest startRequest = OrderPipelineStartRequest.builder()
                .orderId("ORD_HIGH_RISK_004")
                .amount(new BigDecimal("19999.00"))
                .itemType("PHYSICAL")
                .creditScore(480)
                .vipBypass(false)
                .remarks("高危欺诈嫌疑订单")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/pipeline/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andReturn();

        JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String threadId = startJson.path("data").path("threadId").asText();

        // 注入 REJECTED 决断
        OrderPipelineResumeRequest resumeRequest = OrderPipelineResumeRequest.builder()
                .threadId(threadId)
                .action("REJECTED")
                .operator("auditor_wang")
                .comment("用户手机号为空号，风控驳回")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/pipeline/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.state.failedStage").value("manual_audit"));
    }

    @Test
    @DisplayName("端到端验证工作流主动撤销流转至终态 CANCELLED")
    void testCancelSuspendedWorkflow() throws Exception {
        OrderPipelineStartRequest startRequest = OrderPipelineStartRequest.builder()
                .orderId("ORD_CANCEL_005")
                .amount(new BigDecimal("9999.00"))
                .itemType("PHYSICAL")
                .creditScore(520)
                .vipBypass(false)
                .remarks("挂起等待用户主动撤销")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/pipeline/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andReturn();

        JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String threadId = startJson.path("data").path("threadId").asText();

        // 执行主动撤销
        OrderPipelineCancelRequest cancelRequest = OrderPipelineCancelRequest.builder()
                .threadId(threadId)
                .reason("买家主动申请取消未审核订单")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/pipeline/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
