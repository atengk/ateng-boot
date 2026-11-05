package io.github.atengk.boot.ai.tool.approval;

import io.github.atengk.boot.ai.annotation.RiskLevel;
import io.github.atengk.boot.ai.tool.approval.store.InMemoryToolApprovalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高危工具审批工单存储单元测试 (InMemoryToolApprovalStore)
 *
 * @author Ateng
 * @since 2026-11-06
 */
class ToolApprovalStoreTest {

    private InMemoryToolApprovalStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryToolApprovalStore();
    }

    @Test
    @DisplayName("测试工单保存、查询与存在性")
    void testSaveAndFind() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_001")
                .toolName("refundOrder")
                .methodName("refund")
                .targetClassName("com.example.OrderService")
                .argumentsJson("{\"orderId\":\"ORD123\",\"amount\":100.0}")
                .riskLevel(RiskLevel.HIGH)
                .approvalType("FINANCIAL")
                .status(ToolApprovalStatus.PENDING)
                .createTime(LocalDateTime.now())
                .expireTime(LocalDateTime.now().plusMinutes(30))
                .build();

        store.save(ticket, Duration.ofMinutes(30));

        Optional<ToolApprovalTicket> found = store.findById("TICKET_001");
        assertThat(found).isPresent();
        assertThat(found.get().getToolName()).isEqualTo("refundOrder");
        assertThat(found.get().getStatus()).isEqualTo(ToolApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("测试 CAS 状态跃迁与结果更新")
    void testUpdateStatusCas() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("TICKET_002")
                .toolName("deleteUser")
                .status(ToolApprovalStatus.PENDING)
                .build();

        store.save(ticket, Duration.ofMinutes(30));

        // 期望状态不匹配时更新失败
        boolean failUpdated = store.updateStatus("TICKET_002", ToolApprovalStatus.APPROVED,
                ToolApprovalStatus.REJECTED, "reason", null, null);
        assertThat(failUpdated).isFalse();

        // 期望状态匹配时更新成功
        boolean successUpdated = store.updateStatus("TICKET_002", ToolApprovalStatus.PENDING,
                ToolApprovalStatus.APPROVED, "同意执行", "{\"success\":true}", null);
        assertThat(successUpdated).isTrue();

        Optional<ToolApprovalTicket> updated = store.findById("TICKET_002");
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(ToolApprovalStatus.APPROVED);
        assertThat(updated.get().getReviewComment()).isEqualTo("同意执行");
        assertThat(updated.get().getExecutionResultJson()).isEqualTo("{\"success\":true}");
    }

    @Test
    @DisplayName("测试查询待审批列表与删除工单")
    void testListPendingAndDelete() {
        ToolApprovalTicket ticket1 = ToolApprovalTicket.builder()
                .ticketId("T1")
                .status(ToolApprovalStatus.PENDING)
                .build();
        ToolApprovalTicket ticket2 = ToolApprovalTicket.builder()
                .ticketId("T2")
                .status(ToolApprovalStatus.APPROVED)
                .build();

        store.save(ticket1, Duration.ofMinutes(30));
        store.save(ticket2, Duration.ofMinutes(30));

        List<ToolApprovalTicket> pendingList = store.listPending();
        assertThat(pendingList).hasSize(1);
        assertThat(pendingList.get(0).getTicketId()).isEqualTo("T1");

        store.delete("T1");
        assertThat(store.findById("T1")).isEmpty();
        assertThat(store.listPending()).isEmpty();
    }

    @Test
    @DisplayName("测试工单超时自动判定为 EXPIRED")
    void testExpiration() throws InterruptedException {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("T_EXPIRE")
                .status(ToolApprovalStatus.PENDING)
                .build();

        // 存活 50ms
        store.save(ticket, Duration.ofMillis(50));
        Thread.sleep(80);

        Optional<ToolApprovalTicket> found = store.findById("T_EXPIRE");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ToolApprovalStatus.EXPIRED);
        assertThat(store.listPending()).isEmpty();
    }
}
