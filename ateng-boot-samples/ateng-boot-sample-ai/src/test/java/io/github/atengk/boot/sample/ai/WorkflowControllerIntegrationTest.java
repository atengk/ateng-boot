package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.sample.ai.dto.OrderReviewCancelRequest;
import io.github.atengk.boot.sample.ai.dto.OrderReviewResumeRequest;
import io.github.atengk.boot.sample.ai.dto.OrderReviewStartRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认知状态图工作流端到端集成测试 (双模自适应自洽驱动)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("认知状态图工作流与人机协作集成测试 (双模离线自洽驱动)")
class WorkflowControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("端到端验证小额优质订单自动初审通过并流转至终态 COMPLETED")
    void testAutoApproveWorkflow() throws Exception {
        OrderReviewStartRequest request = OrderReviewStartRequest.builder()
                .orderId("ORD_AUTO_PASS_001")
                .amount(new BigDecimal("500"))
                .creditScore(780)
                .remarks("极低风险用户快速放行")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.state.finalStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("端到端验证大额高危订单自动挂起中断 (SUSPENDED) 且快照探查正常")
    void testSuspendedWorkflowAndSnapshot() throws Exception {
        OrderReviewStartRequest request = OrderReviewStartRequest.builder()
                .orderId("ORD_HIGH_RISK_002")
                .amount(new BigDecimal("8888.88"))
                .creditScore(720)
                .remarks("大额高危订单触发人工复核")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.nextNode").value("humanReview"))
                .andExpect(jsonPath("$.data.state.riskLevel").value("HIGH"))
                .andReturn();

        JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString());
        String threadId = startJson.path("data").path("threadId").asText();
        assertThat(threadId).isNotBlank();

        // 验证快照查询接口
        mockMvc.perform(get("/sample/ai/workflow/order-review/snapshot/{threadId}", threadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.threadId").value(threadId))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.nextNode").value("humanReview"));
    }

    @Test
    @DisplayName("端到端验证人工审核注入 PASSED 决断推进至终态 COMPLETED 并标记 APPROVED")
    void testResumeApproved() throws Exception {
        OrderReviewStartRequest startReq = OrderReviewStartRequest.builder()
                .orderId("ORD_HUMAN_PASS_003")
                .amount(new BigDecimal("6000"))
                .creditScore(700)
                .remarks("待审批通过订单")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andReturn();

        String threadId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                .path("data").path("threadId").asText();

        // 人工审批通过
        OrderReviewResumeRequest resumeReq = OrderReviewResumeRequest.builder()
                .threadId(threadId)
                .action("PASSED")
                .comment("用户提供资产证明充分，准予放行")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.finalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.state.archiveTime").isString());
    }

    @Test
    @DisplayName("端到端验证人工审核注入 REJECTED 决断推进至终态 COMPLETED 并标记 REJECTED")
    void testResumeRejected() throws Exception {
        OrderReviewStartRequest startReq = OrderReviewStartRequest.builder()
                .orderId("ORD_HUMAN_REJECT_004")
                .amount(new BigDecimal("7000"))
                .creditScore(580)
                .remarks("待审批驳回订单")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andReturn();

        String threadId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                .path("data").path("threadId").asText();

        // 人工审批驳回
        OrderReviewResumeRequest resumeReq = OrderReviewResumeRequest.builder()
                .threadId(threadId)
                .action("REJECTED")
                .comment("征信逾期严重且无担保，予以驳回")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.finalStatus").value("REJECTED"));
    }

    @Test
    @DisplayName("端到端验证补充资质反思循环：REQUEST_DOCS 重入挂起，随后 PASSED 成功归档")
    void testCycleReflectWithDocSupplement() throws Exception {
        OrderReviewStartRequest startReq = OrderReviewStartRequest.builder()
                .orderId("ORD_CYCLE_DOC_005")
                .amount(new BigDecimal("12000"))
                .creditScore(660)
                .remarks("高额需补件订单")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andReturn();

        String threadId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                .path("data").path("threadId").asText();

        // 第一次审批：要求补件
        OrderReviewResumeRequest requestDocsReq = OrderReviewResumeRequest.builder()
                .threadId(threadId)
                .action("REQUEST_DOCS")
                .additionalDocs("请上传近6个月个人完税证明与收入流水")
                .comment("流水不足，退回补件")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDocsReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.nextNode").value("humanReview"))
                .andExpect(jsonPath("$.data.state.docRetryCount").value(1))
                .andExpect(jsonPath("$.data.state.additionalDocs").value("请上传近6个月个人完税证明与收入流水"));

        // 第二次审批：补件后复核通过
        OrderReviewResumeRequest passReq = OrderReviewResumeRequest.builder()
                .threadId(threadId)
                .action("PASSED")
                .comment("纳税流水真实有效，核验通过")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(passReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.finalStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("端到端验证主动撤销工单 (CANCELLED) 及阻断后续恢复执行")
    void testCancelAndPreventResume() throws Exception {
        OrderReviewStartRequest startReq = OrderReviewStartRequest.builder()
                .orderId("ORD_CANCEL_006")
                .amount(new BigDecimal("9900"))
                .creditScore(710)
                .remarks("待主动取消订单")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andReturn();

        String threadId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                .path("data").path("threadId").asText();

        // 主动撤销工单
        OrderReviewCancelRequest cancelReq = OrderReviewCancelRequest.builder()
                .threadId(threadId)
                .reason("客户已在线下全额现金结清，取消线上信审")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 尝试对已撤销工单恢复审批 -> 触发 CAS 防卫拦截
        OrderReviewResumeRequest resumeReq = OrderReviewResumeRequest.builder()
                .threadId(threadId)
                .action("PASSED")
                .comment("撤销后误操作审批")
                .build();

        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AiResultCode.AI_GRAPH_ALREADY_CANCELLED.getCode()));
    }

    @Test
    @DisplayName("端到端验证 CAS 重复恢复防卫：已完成工单禁止二次恢复审批")
    void testDoubleResumeConflictDefense() throws Exception {
        OrderReviewStartRequest startReq = OrderReviewStartRequest.builder()
                .orderId("ORD_DOUBLE_007")
                .amount(new BigDecimal("6666"))
                .creditScore(710)
                .remarks("防重复恢复测试")
                .build();

        MvcResult startResult = mockMvc.perform(post("/sample/ai/workflow/order-review/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andReturn();

        String threadId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                .path("data").path("threadId").asText();

        OrderReviewResumeRequest resumeReq = OrderReviewResumeRequest.builder()
                .threadId(threadId)
                .action("PASSED")
                .comment("首次审批通过")
                .build();

        // 首次审批成功
        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第二次重复审批 -> 触发 CAS 防卫拦截
        mockMvc.perform(post("/sample/ai/workflow/order-review/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AiResultCode.AI_GRAPH_ALREADY_RESUMED.getCode()));
    }
}
