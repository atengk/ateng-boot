package io.github.atengk.boot.rocketmq.checker;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RocketMQ 全局统一事务回查分发器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqTransactionChecker 单元测试")
class RocketmqTransactionCheckerTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("空 MessageView 安全保底返回 UNKNOWN")
    void testCheckNullMessageViewReturnsUnknown() {
        RocketmqTransactionChecker checker = new RocketmqTransactionChecker();
        assertThat(checker.check(null)).isEqualTo(TransactionResolution.UNKNOWN);
    }

    @Test
    @DisplayName("未找到匹配 Topic 的 Checker 时安全保底返回 UNKNOWN")
    void testCheckNoMatchingTopicReturnsUnknown() {
        RocketmqLocalTransactionChecker orderChecker = mock(RocketmqLocalTransactionChecker.class);
        when(orderChecker.getTopic()).thenReturn("order_topic");
        when(orderChecker.supports("pay_topic")).thenReturn(false);

        RocketmqTransactionChecker checker = new RocketmqTransactionChecker(List.of(orderChecker));

        MessageView messageView = mock(MessageView.class);
        when(messageView.getTopic()).thenReturn("pay_topic");
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-001");
        when(messageView.getMessageId()).thenReturn(messageId);
        when(messageView.getProperties()).thenReturn(Collections.emptyMap());

        TransactionResolution resolution = checker.check(messageView);
        assertThat(resolution).isEqualTo(TransactionResolution.UNKNOWN);
        verify(orderChecker, never()).check(any());
    }

    @Test
    @DisplayName("命中匹配 Topic 的 Checker 并成功返回 COMMIT")
    void testCheckMatchingTopicReturnsCommit() {
        RocketmqLocalTransactionChecker orderChecker = mock(RocketmqLocalTransactionChecker.class);
        when(orderChecker.getTopic()).thenReturn("order_topic");
        when(orderChecker.supports("order_topic")).thenReturn(true);

        MessageView messageView = mock(MessageView.class);
        when(messageView.getTopic()).thenReturn("order_topic");
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-ORDER-001");
        when(messageView.getMessageId()).thenReturn(messageId);
        when(messageView.getProperties()).thenReturn(Collections.emptyMap());
        when(orderChecker.check(messageView)).thenReturn(TransactionResolution.COMMIT);

        RocketmqTransactionChecker checker = new RocketmqTransactionChecker(List.of(orderChecker));
        TransactionResolution resolution = checker.check(messageView);

        assertThat(resolution).isEqualTo(TransactionResolution.COMMIT);
        verify(orderChecker).check(messageView);
    }

    @Test
    @DisplayName("命中匹配 Topic 的 Checker 并成功返回 ROLLBACK")
    void testCheckMatchingTopicReturnsRollback() {
        RocketmqLocalTransactionChecker orderChecker = mock(RocketmqLocalTransactionChecker.class);
        when(orderChecker.getTopic()).thenReturn("order_topic");
        when(orderChecker.supports("order_topic")).thenReturn(true);

        MessageView messageView = mock(MessageView.class);
        when(messageView.getTopic()).thenReturn("order_topic");
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-ORDER-002");
        when(messageView.getMessageId()).thenReturn(messageId);
        when(messageView.getProperties()).thenReturn(Collections.emptyMap());
        when(orderChecker.check(messageView)).thenReturn(TransactionResolution.ROLLBACK);

        RocketmqTransactionChecker checker = new RocketmqTransactionChecker(List.of(orderChecker));
        TransactionResolution resolution = checker.check(messageView);

        assertThat(resolution).isEqualTo(TransactionResolution.ROLLBACK);
        verify(orderChecker).check(messageView);
    }

    @Test
    @DisplayName("业务 Checker 返回 null 时保底降级返回 UNKNOWN")
    void testCheckReturnsNullDefaultsToUnknown() {
        RocketmqLocalTransactionChecker orderChecker = mock(RocketmqLocalTransactionChecker.class);
        when(orderChecker.getTopic()).thenReturn("order_topic");
        when(orderChecker.supports("order_topic")).thenReturn(true);

        MessageView messageView = mock(MessageView.class);
        when(messageView.getTopic()).thenReturn("order_topic");
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-ORDER-003");
        when(messageView.getMessageId()).thenReturn(messageId);
        when(messageView.getProperties()).thenReturn(Collections.emptyMap());
        when(orderChecker.check(messageView)).thenReturn(null);

        RocketmqTransactionChecker checker = new RocketmqTransactionChecker(List.of(orderChecker));
        TransactionResolution resolution = checker.check(messageView);

        assertThat(resolution).isEqualTo(TransactionResolution.UNKNOWN);
    }

    @Test
    @DisplayName("业务 Checker 抛出异常时捕获并保底返回 UNKNOWN")
    void testCheckThrowsExceptionReturnsUnknown() {
        RocketmqLocalTransactionChecker orderChecker = mock(RocketmqLocalTransactionChecker.class);
        when(orderChecker.getTopic()).thenReturn("order_topic");
        when(orderChecker.supports("order_topic")).thenReturn(true);

        MessageView messageView = mock(MessageView.class);
        when(messageView.getTopic()).thenReturn("order_topic");
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-ORDER-004");
        when(messageView.getMessageId()).thenReturn(messageId);
        when(messageView.getProperties()).thenReturn(Collections.emptyMap());
        when(orderChecker.check(messageView)).thenThrow(new RuntimeException("Database timeout"));

        RocketmqTransactionChecker checker = new RocketmqTransactionChecker(List.of(orderChecker));
        TransactionResolution resolution = checker.check(messageView);

        assertThat(resolution).isEqualTo(TransactionResolution.UNKNOWN);
    }

    @Test
    @DisplayName("回查执行时成功激活四维上下文并在执行完毕后完全清理现场")
    void testContextActivationAndDeterministicCleanup() {
        Map<String, String> properties = new HashMap<>();
        properties.put(MessageHeaderConstants.TRACE_ID, "trace-tx-12345");
        properties.put(MessageHeaderConstants.TENANT_ID, "tenant-999");
        properties.put(MessageHeaderConstants.USER_ID, "user-888");

        MessageView messageView = mock(MessageView.class);
        when(messageView.getTopic()).thenReturn("order_topic");
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-CTX-001");
        when(messageView.getMessageId()).thenReturn(messageId);
        when(messageView.getProperties()).thenReturn(properties);

        AtomicBoolean contextVerified = new AtomicBoolean(false);

        RocketmqLocalTransactionChecker checkerSPI = new RocketmqLocalTransactionChecker() {
            @Override
            public String getTopic() {
                return "order_topic";
            }

            @Override
            public TransactionResolution check(MessageView mv) {
                assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("trace-tx-12345");
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-999");
                assertThat(UserContextHolder.get()).isEqualTo("user-888");
                contextVerified.set(true);
                return TransactionResolution.COMMIT;
            }
        };

        RocketmqTransactionChecker checker = new RocketmqTransactionChecker();
        checker.addChecker(checkerSPI);

        TransactionResolution resolution = checker.check(messageView);

        assertThat(resolution).isEqualTo(TransactionResolution.COMMIT);
        assertThat(contextVerified.get()).isTrue();

        // 验证执行完毕后现场完全还原，零污染
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.get()).isNull();
    }
}
