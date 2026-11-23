package io.github.atengk.boot.ai.tool.approval;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.annotation.RiskLevel;
import io.github.atengk.boot.ai.annotation.SensitiveTool;
import io.github.atengk.boot.ai.properties.ToolApprovalProperties;
import io.github.atengk.boot.ai.tool.SandboxedToolExecutor;
import io.github.atengk.boot.ai.tool.ToolExecutionSandbox;
import io.github.atengk.boot.ai.tool.approval.store.InMemoryToolApprovalStore;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感高危工具执行拦截与人机协同审批闭环端到端测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class SensitiveToolInterceptionTest {

    private ToolApprovalTemplate approvalTemplate;
    private OrderManagementTools toolsBean;
    private Map<ToolSpecification, ToolExecutor> sandboxedTools;

    @BeforeEach
    void setUp() {
        InMemoryToolApprovalStore store = new InMemoryToolApprovalStore();
        ToolApprovalProperties properties = new ToolApprovalProperties();
        approvalTemplate = new ToolApprovalTemplate(store, properties, null);

        toolsBean = new OrderManagementTools();
        approvalTemplate.registerTargetBean(toolsBean);

        sandboxedTools = ToolExecutionSandbox.wrapToolBean(toolsBean, approvalTemplate);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("普通工具不标注 @SensitiveTool 时直接执行生效")
    void testNormalToolExecutesDirectly() {
        ToolSpecification getOrderSpec = findSpec("getOrderStatus");
        ToolExecutor executor = sandboxedTools.get(getOrderSpec);
        assertThat(executor).isNotNull();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("getOrderStatus")
                .arguments("{\"orderId\":\"ORD_888\"}")
                .build();

        String result = executor.execute(request, null);
        assertThat(result).isEqualTo("订单已发货: ORD_888");
        assertThat(toolsBean.getRefundExecutedCount()).isZero();
    }

    @Test
    @DisplayName("标注 @SensitiveTool 的高危工具被沙箱拦截挂起，生成工单并不直接执行")
    void testSensitiveToolInterceptedAndApproved() {
        ToolSpecification refundSpec = findSpec("refundOrder");
        ToolExecutor executor = sandboxedTools.get(refundSpec);
        assertThat(executor).isNotNull();

        // 1. 模拟大模型调用高危退款工具
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("refundOrder")
                .arguments("{\"orderId\":\"ORD_REFUND_001\",\"refundAmount\":299.0}")
                .build();

        String interceptedResponse = executor.execute(request, null);

        // 验证沙箱返回包含工单挂起标识的提示文本
        assertThat(SandboxedToolExecutor.isApprovalRequired(interceptedResponse)).isTrue();
        assertThat(interceptedResponse)
                .contains(SandboxedToolExecutor.APPROVAL_REQUIRED_PREFIX)
                .contains("ORDER_REFUND")
                .contains("涉及用户退款出金操作");

        // 确保目标真实业务逻辑尚未执行
        assertThat(toolsBean.getRefundExecutedCount()).isZero();

        // 2. 从响应中结构化提取工单 ID
        String ticketId = SandboxedToolExecutor.extractTicketId(interceptedResponse).orElseThrow();

        // 3. 验证工单状态为 PENDING
        ToolApprovalTicket ticket = approvalTemplate.getTicket(ticketId).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(ticket.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(ticket.getApprovalType()).isEqualTo("ORDER_REFUND");

        // 4. 模拟管理员通过审批门面核准工单
        ToolApprovalResult approvalResult = approvalTemplate.approve(ticketId, "财务审批核准通过退款");

        assertThat(approvalResult.isSuccess()).isTrue();
        assertThat(approvalResult.getStatus()).isEqualTo(ToolApprovalStatus.APPROVED);
        assertThat(approvalResult.getResult()).isEqualTo("退款成功: ORD_REFUND_001, 金额: 299.0");

        // 5. 确保此时目标真实业务方法被成功调用
        assertThat(toolsBean.getRefundExecutedCount()).isEqualTo(1);
    }

    private ToolSpecification findSpec(String methodName) {
        for (ToolSpecification spec : sandboxedTools.keySet()) {
            if (spec.name().equals(methodName)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("未找到工具方法: " + methodName);
    }

    public static class OrderManagementTools {
        private int refundExecutedCount = 0;

        @Tool("根据订单号查询订单状态")
        public String getOrderStatus(String orderId) {
            return "订单已发货: " + orderId;
        }

        @Tool("对指定订单执行全额退款操作")
        @SensitiveTool(riskLevel = RiskLevel.HIGH, approvalType = "ORDER_REFUND", description = "涉及用户退款出金操作")
        public String refundOrder(String orderId, Double refundAmount) {
            this.refundExecutedCount++;
            return "退款成功: " + orderId + ", 金额: " + refundAmount;
        }

        public int getRefundExecutedCount() {
            return refundExecutedCount;
        }
    }
}
