package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqTransactionFailureCallback;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.RecallReceipt;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RocketmqTemplate 生产门面单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqTemplate 生产门面单元测试")
class RocketmqTemplateTest {

    private Producer producer;
    private AtengRocketmqProperties properties;
    private RocketmqTemplate rocketmqTemplate;
    private SendReceipt mockReceipt;
    private MessageId mockMessageId;

    @BeforeEach
    void setUp() throws Exception {
        producer = mock(Producer.class);
        properties = new AtengRocketmqProperties();
        rocketmqTemplate = new RocketmqTemplate(producer, properties);

        mockReceipt = mock(SendReceipt.class);
        mockMessageId = mock(MessageId.class);
        when(mockMessageId.toString()).thenReturn("MSG-001");
        when(mockReceipt.getMessageId()).thenReturn(mockMessageId);

        when(producer.send(any(Message.class))).thenReturn(mockReceipt);
        when(producer.sendAsync(any(Message.class))).thenReturn(CompletableFuture.completedFuture(mockReceipt));

        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-test-888");
        TenantContextHolder.setTenantId("tenant-999");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证同步发送：四维上下文、Tag、Keys、Payload 正确封装并返回 Receipt")
    void testSendSync() throws Exception {
        Map<String, String> payload = Collections.singletonMap("orderNo", "ORD-12345");
        SendReceipt receipt = rocketmqTemplate.send("order_topic", "create", "BIZ-ORD-12345", payload);

        assertThat(receipt).isSameAs(mockReceipt);
        assertThat(rocketmqTemplate.getProducer()).isSameAs(producer);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getTopic()).isEqualTo("order_topic");
        assertThat(captured.getTag()).contains("create");
        assertThat(captured.getKeys()).contains("BIZ-ORD-12345");
        assertThat(captured.getProperties()).containsEntry(MessageHeaderConstants.TRACE_ID, "trace-test-888");
        assertThat(captured.getProperties()).containsEntry(MessageHeaderConstants.TENANT_ID, "tenant-999");
        assertThat(captured.getProperties()).containsEntry(MessageHeaderConstants.BIZ_KEY, "BIZ-ORD-12345");

        byte[] body = new byte[captured.getBody().remaining()];
        captured.getBody().get(body);
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("ORD-12345");
    }

    @Test
    @DisplayName("验证异步非阻塞发送：sendAsync 返回正常 CompletableFuture")
    void testSendAsync() {
        CompletableFuture<SendReceipt> future = rocketmqTemplate.sendAsync("notice_topic", "hello");
        assertThat(future).isCompletedWithValue(mockReceipt);
    }

    @Test
    @DisplayName("验证延时发送：sendDelayed 正确换算毫秒级 deliveryTimestamp")
    void testSendDelayed() throws Exception {
        long beforeTime = System.currentTimeMillis();
        rocketmqTemplate.sendDelayed("delay_topic", "tagD", "delay-payload", Duration.ofMinutes(10));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getDeliveryTimestamp()).isPresent();
        long timestamp = captured.getDeliveryTimestamp().get();
        assertThat(timestamp).isGreaterThanOrEqualTo(beforeTime + 600000L);
    }

    @Test
    @DisplayName("验证定时发送：sendDeliverAt 精准指定 Instant 绝对时间戳")
    void testSendDeliverAt() throws Exception {
        Instant deliverAt = Instant.ofEpochMilli(1800000000000L);
        rocketmqTemplate.sendDeliverAt("schedule_topic", "tagS", "schedule-payload", deliverAt);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getDeliveryTimestamp()).contains(1800000000000L);
    }

    @Test
    @DisplayName("验证 FIFO 顺序消息：sendOrderly 正确设置 messageGroup 属性")
    void testSendOrderly() throws Exception {
        rocketmqTemplate.sendOrderly("order_fifo_topic", "tagO", "order-100", "GROUP-ORD-100");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getMessageGroup()).contains("GROUP-ORD-100");
    }

    @Test
    @DisplayName("验证大报文 Fail-Fast：超过配置上限立即抛出 MessageDeliveryException")
    void testMaxPayloadSizeFailFast() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofBytes(15));

        assertThatThrownBy(() -> rocketmqTemplate.send("large_topic", "this is a very large payload exceeding 15 bytes"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("超过配置上限");
    }

    @Test
    @DisplayName("验证透明 GZIP 压缩：超过阈值自动压缩并在 Properties 标记 X-Compression")
    void testTransparentGzipCompression() throws Exception {
        properties.getProducer().setCompressionThreshold(DataSize.ofBytes(20));

        String largeText = "RocketMQ GZIP Transparent Compression Test! ".repeat(10);
        rocketmqTemplate.send("gzip_topic", largeText);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getProperties()).containsEntry(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);

        byte[] compressedBytes = new byte[captured.getBody().remaining()];
        captured.getBody().get(compressedBytes);

        byte[] decompressed = RocketmqMessageHelper.decompressGzip(compressedBytes);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).contains("RocketMQ GZIP Transparent");
    }

    @Test
    @DisplayName("验证 Topic 命名正则防卫：非法 Topic 抛出 IllegalArgumentException")
    void testTopicRegexValidation() {
        assertThatThrownBy(() -> rocketmqTemplate.send("invalid.topic", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须符合正则");

        assertThatThrownBy(() -> rocketmqTemplate.send("invalid@topic", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须符合正则");
    }

    @Test
    @DisplayName("验证异常转译：底层 ClientException 被转译为 MessageDeliveryException 并保留 cause")
    void testExceptionTranslation() throws Exception {
        when(producer.send(any(Message.class))).thenThrow(new ClientException("Broker connection timeout"));

        assertThatThrownBy(() -> rocketmqTemplate.send("fail_topic", "payload"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Broker connection timeout")
                .hasCauseInstanceOf(ClientException.class);
    }

    @Test
    @DisplayName("验证异步发送异常转译：底层 ClientException 被转译为 MessageDeliveryException 并保留 cause")
    void testSendAsyncExceptionTranslation() {
        CompletableFuture<SendReceipt> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new ClientException("Async broker timeout"));
        when(producer.sendAsync(any(Message.class))).thenReturn(failedFuture);

        CompletableFuture<SendReceipt> future = rocketmqTemplate.sendAsync("async_topic", "payload");
        assertThat(future).isCompletedExceptionally();

        assertThatThrownBy(future::join)
                .hasCauseInstanceOf(MessageDeliveryException.class)
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex.getCause();
                    assertThat(mde.getMessage()).contains("Async broker timeout");
                    assertThat(mde.getCause()).isInstanceOf(ClientException.class);
                });
    }

    @Test
    @DisplayName("验证 sendAfterCommit：无活动事务时立即物理发送")
    void testSendAfterCommitWithoutTransaction() throws Exception {
        rocketmqTemplate.sendAfterCommit("tx_topic", "hello-no-tx");
        verify(producer).send(any(Message.class));
    }

    @Test
    @DisplayName("验证 sendAfterCommit：存在活动事务时在 afterCommit 钩子中触发，失败回调 SPI")
    void testSendAfterCommitWithTransactionAndFailureCallback() throws Exception {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        RocketmqTransactionFailureCallback failureCallback = (topic, bizKey, payload, cause) -> {
            callbackInvoked.set(true);
            assertThat(topic).isEqualTo("tx_active_topic");
            assertThat(bizKey).isEqualTo("TX-BIZ-1");
        };

        RocketmqTemplate txTemplate = new RocketmqTemplate(producer, properties, failureCallback);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // 注册事务同步后发信
            txTemplate.sendAfterCommit("tx_active_topic", "tagTx", "TX-BIZ-1", "tx-payload", null);

            // 尚未触发 afterCommit 时不应该物理发信
            verify(producer, never()).send(any(Message.class));

            // 模拟发送异常
            when(producer.send(any(Message.class))).thenThrow(new RuntimeException("Simulated network down"));

            // 触发所有 afterCommit 钩子
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            // 验证触发了物理发送且捕获异常后回调了 SPI
            verify(producer).send(any(Message.class));
            assertThat(callbackInvoked).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：本地事务返回 COMMIT 时自动提交半消息")
    void testSendTransactionCommit() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenReturn(mockReceipt);

        SendReceipt receipt = rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-001",
                Collections.singletonMap("amount", 100),
                r -> TransactionResolution.COMMIT);

        assertThat(receipt).isSameAs(mockReceipt);
        verify(producer).beginTransaction();
        verify(producer).send(any(Message.class), eq(mockTx));
        verify(mockTx).commit();
        verify(mockTx, never()).rollback();
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：本地事务返回 ROLLBACK 时自动回滚半消息")
    void testSendTransactionRollback() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenReturn(mockReceipt);

        SendReceipt receipt = rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-002",
                Collections.singletonMap("amount", 200),
                r -> TransactionResolution.ROLLBACK);

        assertThat(receipt).isSameAs(mockReceipt);
        verify(producer).beginTransaction();
        verify(producer).send(any(Message.class), eq(mockTx));
        verify(mockTx, never()).commit();
        verify(mockTx).rollback();
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：本地事务返回 UNKNOWN 时不显式 Commit 或 Rollback，等待回查")
    void testSendTransactionUnknown() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenReturn(mockReceipt);

        SendReceipt receipt = rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-003",
                Collections.singletonMap("amount", 300),
                r -> TransactionResolution.UNKNOWN);

        assertThat(receipt).isSameAs(mockReceipt);
        verify(producer).beginTransaction();
        verify(producer).send(any(Message.class), eq(mockTx));
        verify(mockTx, never()).commit();
        verify(mockTx, never()).rollback();
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：本地事务执行异常时触发回滚并向外抛出异常")
    void testSendTransactionExecutorThrowsException() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenReturn(mockReceipt);

        assertThatThrownBy(() -> rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-004",
                Collections.singletonMap("amount", 400),
                r -> {
                    throw new IllegalStateException("余额不足");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("余额不足");

        verify(mockTx).rollback();
        verify(mockTx, never()).commit();
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：半消息投递失败时回滚事务句柄并抛出 MessageDeliveryException")
    void testSendTransactionHalfMessageSendFailed() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenThrow(new RuntimeException("Broker rejected"));

        assertThatThrownBy(() -> rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-005",
                Collections.singletonMap("amount", 500),
                r -> TransactionResolution.COMMIT))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RocketMQ 事务半消息投递失败");

        verify(mockTx).rollback();
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：事务提交失败抛出 MessageDeliveryException")
    void testSendTransactionCommitFailed() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenReturn(mockReceipt);
        doThrow(new RuntimeException("Commit timeout")).when(mockTx).commit();

        assertThatThrownBy(() -> rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-006",
                Collections.singletonMap("amount", 600),
                r -> TransactionResolution.COMMIT))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RocketMQ 事务提交失败");
    }

    @Test
    @DisplayName("验证原生 2PC 分布式事务：事务回滚失败抛出 MessageDeliveryException")
    void testSendTransactionRollbackFailed() throws Exception {
        Transaction mockTx = mock(Transaction.class);
        when(producer.beginTransaction()).thenReturn(mockTx);
        when(producer.send(any(Message.class), eq(mockTx))).thenReturn(mockReceipt);
        doThrow(new RuntimeException("Rollback timeout")).when(mockTx).rollback();

        assertThatThrownBy(() -> rocketmqTemplate.sendTransaction(
                "tx_order_topic",
                "create",
                "TX-ORD-007",
                Collections.singletonMap("amount", 700),
                r -> TransactionResolution.ROLLBACK))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RocketMQ 事务回滚失败");
    }

    // =========================================================================
    // 单向发信 (sendOneway) 测试
    // =========================================================================

    @Test
    @DisplayName("验证单向发送：极速非阻塞投递成功")
    void testSendOnewaySuccess() {
        Map<String, String> payload = Collections.singletonMap("log", "audit_event");
        rocketmqTemplate.sendOneway("audit_topic", "audit", "BIZ-AUDIT-001", payload);

        verify(producer, times(1)).sendAsync(any(Message.class));
    }

    @Test
    @DisplayName("验证单向发送：底层异步发信异常时不向调用方抛错")
    void testSendOnewayFailureDoesNotThrow() {
        CompletableFuture<SendReceipt> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker network failure"));
        when(producer.sendAsync(any(Message.class))).thenReturn(failedFuture);

        Map<String, String> payload = Collections.singletonMap("log", "audit_event");
        rocketmqTemplate.sendOneway("audit_topic", "audit", "BIZ-AUDIT-002", payload);

        verify(producer, times(1)).sendAsync(any(Message.class));
    }

    // =========================================================================
    // 批量发送 (sendBatch / sendBatchAsync) 测试
    // =========================================================================

    @Test
    @DisplayName("验证批量发送：正常并发分发并汇聚回执列表")
    void testSendBatchSuccess() {
        List<Map<String, Object>> payloads = List.of(
                Collections.singletonMap("id", 1),
                Collections.singletonMap("id", 2),
                Collections.singletonMap("id", 3)
        );

        List<SendReceipt> receipts = rocketmqTemplate.sendBatch("batch_order_topic", "batch", payloads);

        assertThat(receipts).hasSize(3);
        verify(producer, times(3)).sendAsync(any(Message.class));
    }

    @Test
    @DisplayName("验证批量发送：空列表抛出 IllegalArgumentException")
    void testSendBatchEmptyPayloadsThrowsException() {
        assertThatThrownBy(() -> rocketmqTemplate.sendBatch("batch_order_topic", Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloads 不能为空且不可为空集合");
    }

    @Test
    @DisplayName("验证批量发送：超出 maxBatchSize 上限触发 Fail-Fast")
    void testSendBatchExceedsMaxBatchSizeThrowsException() {
        List<String> largeBatch = new ArrayList<>(501);
        for (int i = 0; i < 501; i++) {
            largeBatch.add("item-" + i);
        }

        assertThatThrownBy(() -> rocketmqTemplate.sendBatch("batch_order_topic", largeBatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出配置上限 [500]");
    }

    @Test
    @DisplayName("验证批量发送：异步批量返回 CompletableFuture")
    void testSendBatchAsyncSuccess() throws Exception {
        List<String> payloads = List.of("msg1", "msg2");
        CompletableFuture<List<SendReceipt>> future = rocketmqTemplate.sendBatchAsync("batch_order_topic", payloads);

        List<SendReceipt> receipts = future.get();
        assertThat(receipts).hasSize(2);
    }

    @Test
    @DisplayName("验证批量发送接入受管 Executor：正常并发调度并成功返回")
    void testSendBatchAsyncWithManagedExecutor() throws Exception {
        java.util.concurrent.Executor managedExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            RocketmqTemplate templateWithExecutor = new RocketmqTemplate(producer, properties, (RocketmqTransactionFailureCallback) null, managedExecutor);
            List<String> payloads = List.of("msg1", "msg2");
            CompletableFuture<List<SendReceipt>> future = templateWithExecutor.sendBatchAsync("batch_order_topic", payloads);

            List<SendReceipt> receipts = future.get();
            assertThat(receipts).hasSize(2);
        } finally {
            ((java.util.concurrent.ExecutorService) managedExecutor).shutdown();
        }
    }

    @Test
    @DisplayName("验证批量发送异常转译：双层嵌套异常被彻底解包为 MessageDeliveryException")
    void testSendBatchUnwrapsNestedExceptionsDirectly() {
        when(producer.sendAsync(any(Message.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Simulated broker network drop")));

        List<String> payloads = List.of("item1", "item2");

        assertThatThrownBy(() -> rocketmqTemplate.sendBatch("batch_fail_topic", payloads))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Simulated broker network drop");
    }

    // =========================================================================
    // 定时消息撤回 (recallMessage / recallMessageAsync) 测试
    // =========================================================================

    @Test
    @DisplayName("验证定时消息撤回：成功返回 RecallReceipt")
    void testRecallMessageSuccess() {
        RecallReceipt mockRecallReceipt = mock(RecallReceipt.class);
        when(mockRecallReceipt.getMessageId()).thenReturn(mockMessageId);
        when(producer.recallMessageAsync("order_delay_topic", "HANDLE-DELAY-001"))
                .thenReturn(CompletableFuture.completedFuture(mockRecallReceipt));

        RecallReceipt receipt = rocketmqTemplate.recallMessage("order_delay_topic", "HANDLE-DELAY-001");

        assertThat(receipt).isSameAs(mockRecallReceipt);
        verify(producer).recallMessageAsync("order_delay_topic", "HANDLE-DELAY-001");
    }

    @Test
    @DisplayName("验证定时消息撤回：空 topic 或 recallHandle 拦截")
    void testRecallMessageValidation() {
        assertThatThrownBy(() -> rocketmqTemplate.recallMessage("", "HANDLE-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic 不能为空");

        assertThatThrownBy(() -> rocketmqTemplate.recallMessage("order_topic", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recallHandle 不能为空");
    }

    @Test
    @DisplayName("验证定时消息撤回：底层失败转译为 MessageDeliveryException")
    void testRecallMessageFailureThrowsException() {
        CompletableFuture<RecallReceipt> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Message already delivered"));
        when(producer.recallMessageAsync("order_delay_topic", "HANDLE-EXPIRED"))
                .thenReturn(failedFuture);

        assertThatThrownBy(() -> rocketmqTemplate.recallMessage("order_delay_topic", "HANDLE-EXPIRED"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RocketMQ 撤回定时消息失败")
                .hasRootCauseInstanceOf(RuntimeException.class);
    }
}
