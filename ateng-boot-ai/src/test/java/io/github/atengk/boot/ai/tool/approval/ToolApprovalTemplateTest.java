package io.github.atengk.boot.ai.tool.approval;

import io.github.atengk.boot.ai.annotation.RiskLevel;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.ToolApprovalProperties;
import io.github.atengk.boot.ai.tool.approval.store.InMemoryToolApprovalStore;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 高危工具审批统一门面单元测试 (ToolApprovalTemplateTest)
 *
 * @author Ateng
 * @since 2026-11-06
 */
class ToolApprovalTemplateTest {

    private InMemoryToolApprovalStore store;
    private ToolApprovalTemplate template;
    private MockFinanceService mockFinanceService;

    @BeforeEach
    void setUp() {
        store = new InMemoryToolApprovalStore();
        ToolApprovalProperties properties = new ToolApprovalProperties();
        template = new ToolApprovalTemplate(store, properties, null);

        mockFinanceService = new MockFinanceService();
        template.registerTargetBean(mockFinanceService);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("测试提交工单与待审批列表查询")
    void testSubmitAndList() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_100")
                .toolName("transferFunds")
                .methodName("transfer")
                .targetClassName(MockFinanceService.class.getName())
                .argumentsJson("{\"fromAccount\":\"A100\",\"toAccount\":\"B200\",\"amount\":500.0}")
                .riskLevel(RiskLevel.HIGH)
                .approvalType("TRANSFER")
                .build();

        template.submit(ticket, Duration.ofMinutes(10));

        Optional<ToolApprovalTicket> retrieved = template.getTicket("TICKET_100");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getStatus()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(template.listPending()).hasSize(1);
    }

    @Test
    @DisplayName("测试审批通过与受管反射重放执行")
    void testApproveAndReplayExecution() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_101")
                .toolName("transferFunds")
                .methodName("transfer")
                .targetClassName(MockFinanceService.class.getName())
                .argumentsJson("{\"fromAccount\":\"ACC_001\",\"toAccount\":\"ACC_002\",\"amount\":888.88}")
                .riskLevel(RiskLevel.HIGH)
                .approvalType("TRANSFER")
                .tenantId("TENANT_CORP_01")
                .callerUserId("USER_OPERATOR_99")
                .traceId("trace-hitl-test-101")
                .build();

        template.submit(ticket, Duration.ofMinutes(10));

        // 模拟当前操作员环境并设置调用线程初始上下文
        UserContextHolder.setUserId("ADMIN_APPROVER");
        TenantContextHolder.setTenantId("ADMIN_TENANT");
        MDC.put("traceId", "trace-admin");

        // 模拟管理员审批通过
        ToolApprovalResult result = template.approve("TICKET_101", "财务总监审批核准通过");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ToolApprovalStatus.APPROVED);
        assertThat(result.getResult()).isEqualTo("转账成功: ACC_001 -> ACC_002, 金额: 888.88");

        // 验证目标业务服务被真实调用，且捕获到了工单原始的四维上下文
        assertThat(mockFinanceService.getExecutedCount()).isEqualTo(1);
        assertThat(mockFinanceService.getLastCapturedTenantId()).isEqualTo("TENANT_CORP_01");
        assertThat(mockFinanceService.getLastCapturedUserId()).isEqualTo("USER_OPERATOR_99");
        assertThat(mockFinanceService.getLastCapturedTraceId()).isEqualTo("trace-hitl-test-101");

        // 验证调用线程在审批完成后，现场被准确恢复为原始状态
        assertThat(UserContextHolder.getUserId()).isEqualTo("ADMIN_APPROVER");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("ADMIN_TENANT");
        assertThat(MDC.get("traceId")).isEqualTo("trace-admin");

        // 验证工单状态已跃迁为 APPROVED
        ToolApprovalTicket updatedTicket = template.getTicket("TICKET_101").orElseThrow();
        assertThat(updatedTicket.getStatus()).isEqualTo(ToolApprovalStatus.APPROVED);
        assertThat(updatedTicket.getReviewComment()).isEqualTo("财务总监审批核准通过");
        assertThat(updatedTicket.getExecutionResultJson()).contains("转账成功");
    }

    @Test
    @DisplayName("测试审批重放时防重复执行：并发双击时仅允许单次物理重放")
    void testAntiConcurrentDuplicateApprove() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_CONCURRENT")
                .toolName("transferFunds")
                .methodName("transfer")
                .targetClassName(MockFinanceService.class.getName())
                .argumentsJson("{\"fromAccount\":\"ACC_001\",\"toAccount\":\"ACC_002\",\"amount\":100.0}")
                .riskLevel(RiskLevel.HIGH)
                .build();
        template.submit(ticket, Duration.ofMinutes(10));

        ToolApprovalResult firstResult = template.approve("TICKET_CONCURRENT", "第一次审批");
        assertThat(firstResult.isSuccess()).isTrue();
        assertThat(mockFinanceService.getExecutedCount()).isEqualTo(1);

        // 第二次审批应该被 CAS 拦截并抛出无效状态异常
        assertThatThrownBy(() -> template.approve("TICKET_CONCURRENT", "第二次审批双击"))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("处于非待审批状态");

        // 确保目标真实业务方法绝未被调用第二次
        assertThat(mockFinanceService.getExecutedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试审批拒绝不执行目标方法")
    void testReject() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_102")
                .toolName("transferFunds")
                .methodName("transfer")
                .targetClassName(MockFinanceService.class.getName())
                .argumentsJson("{\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":100.0}")
                .riskLevel(RiskLevel.CRITICAL)
                .build();

        template.submit(ticket, Duration.ofMinutes(10));

        ToolApprovalResult result = template.reject("TICKET_102", "风控拦截，疑似非本人操作");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ToolApprovalStatus.REJECTED);
        assertThat(result.getErrorMessage()).isEqualTo("风控拦截，疑似非本人操作");

        // 确保目标方法未被调用
        assertThat(mockFinanceService.getExecutedCount()).isZero();

        ToolApprovalTicket updatedTicket = template.getTicket("TICKET_102").orElseThrow();
        assertThat(updatedTicket.getStatus()).isEqualTo(ToolApprovalStatus.REJECTED);
    }

    @Test
    @DisplayName("测试对不存在或已处理工单重复审批抛出受管异常")
    void testApproveInvalidStatus() {
        assertThatThrownBy(() -> template.approve("NON_EXIST_ID", "comment"))
                .isInstanceOf(AiException.class)
                .matches(e -> ((AiException) e).getCode() == AiResultCode.AI_TOOL_APPROVAL_NOT_FOUND.getCode());

        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_103")
                .toolName("transferFunds")
                .methodName("transfer")
                .targetClassName(MockFinanceService.class.getName())
                .build();
        template.submit(ticket, Duration.ofMinutes(10));
        template.reject("TICKET_103", "先拒绝");

        assertThatThrownBy(() -> template.approve("TICKET_103", "再次批准"))
                .isInstanceOf(AiException.class)
                .matches(e -> ((AiException) e).getCode() == AiResultCode.AI_TOOL_APPROVAL_INVALID_STATUS.getCode());
    }

    @Test
    @DisplayName("测试目标方法抛出业务异常时的错误记录与结果返回")
    void testTargetMethodThrowsException() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_FAIL")
                .toolName("throwErrorMethod")
                .methodName("failMethod")
                .targetClassName(MockFinanceService.class.getName())
                .argumentsJson("{}")
                .build();

        template.submit(ticket, Duration.ofMinutes(10));

        ToolApprovalResult result = template.approve("TICKET_FAIL", "批准执行但方法会报错");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("模拟业务故障: 账户余额不足");
    }

    public static class MockFinanceService {
        private int executedCount = 0;
        private String lastCapturedTenantId;
        private String lastCapturedUserId;
        private String lastCapturedTraceId;

        public String transfer(String fromAccount, String toAccount, Double amount) {
            this.executedCount++;
            this.lastCapturedTenantId = TenantContextHolder.getTenantId();
            this.lastCapturedUserId = UserContextHolder.getUserId();
            this.lastCapturedTraceId = MDC.get("traceId");
            return String.format("转账成功: %s -> %s, 金额: %.2f", fromAccount, toAccount, amount);
        }

        public String failMethod() {
            throw new IllegalArgumentException("模拟业务故障: 账户余额不足");
        }

        public int getExecutedCount() {
            return executedCount;
        }

        public String getLastCapturedTenantId() {
            return lastCapturedTenantId;
        }

        public String getLastCapturedUserId() {
            return lastCapturedUserId;
        }

        public String getLastCapturedTraceId() {
            return lastCapturedTraceId;
        }
    }
}
