package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
public class RocketmqTemplateTest {

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
        UserContextHolder.clear();
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
    @DisplayName("验证延时/定时全对称重载：携带 userProperties、异步简化重载与系统追踪头防污染")
    void testDelayedAndDeliverAtUserPropertiesAndSimplifiedOverloads() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("region", "cn-hangzhou");
        props.put("amount", "288");
        props.put(MessageHeaderConstants.TRACE_ID, "spoofed-trace");
        props.put(MessageHeaderConstants.TENANT_ID, "spoofed-tenant");
        props.put(MessageHeaderConstants.BIZ_KEY, "spoofed-biz");
        props.put(RocketmqConstants.HEADER_COMPRESSION, "fake-zip");
        props.put("__RETRY_FLAG", "spoofed-internal");

        Instant deliverAt = Instant.ofEpochMilli(1800000001000L);

        rocketmqTemplate.sendDelayed("delay_topic", "tagD", "BIZ-D1", "d1", Duration.ofSeconds(30), props);
        rocketmqTemplate.sendDelayedAsync("delay_topic", "d2", Duration.ofSeconds(30)).get();
        rocketmqTemplate.sendDelayedAsync("delay_topic", "tagD", "d3", Duration.ofSeconds(30)).get();
        rocketmqTemplate.sendDelayedAsync("delay_topic", "tagD", "BIZ-D4", "d4", Duration.ofSeconds(30), props).get();

        rocketmqTemplate.sendDeliverAt("schedule_topic", "tagS", "BIZ-S1", "s1", deliverAt, props);
        rocketmqTemplate.sendDeliverAtAsync("schedule_topic", "s2", deliverAt).get();
        rocketmqTemplate.sendDeliverAtAsync("schedule_topic", "tagS", "s3", deliverAt).get();
        rocketmqTemplate.sendDeliverAtAsync("schedule_topic", "tagS", "BIZ-S4", "s4", deliverAt, props).get();

        ArgumentCaptor<Message> syncCaptor = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(2)).send(syncCaptor.capture());
        ArgumentCaptor<Message> asyncCaptor = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(6)).sendAsync(asyncCaptor.capture());

        for (Message msg : List.of(
                syncCaptor.getAllValues().get(0),
                syncCaptor.getAllValues().get(1),
                asyncCaptor.getAllValues().get(2),
                asyncCaptor.getAllValues().get(5))) {
            assertThat(msg.getProperties()).containsEntry("region", "cn-hangzhou");
            assertThat(msg.getProperties()).containsEntry("amount", "288");
            assertThat(msg.getProperties()).containsEntry(MessageHeaderConstants.TRACE_ID, "trace-test-888");
            assertThat(msg.getProperties()).containsEntry(MessageHeaderConstants.TENANT_ID, "tenant-999");
            assertThat(msg.getProperties().get(MessageHeaderConstants.BIZ_KEY)).doesNotContain("spoofed-biz");
            assertThat(msg.getProperties()).doesNotContainKey("__RETRY_FLAG");
            assertThat(msg.getProperties().get(RocketmqConstants.HEADER_COMPRESSION)).isNull();
        }
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
    @DisplayName("验证事务失败回调 SPI：既有 4 参实现向后兼容，新 6 参实现可获取完整 tag 与 traceId")
    void testTransactionFailureCallbackSixParamBackwardCompatibility() {
        RuntimeException rootCause = new RuntimeException("Broker unavailable");
        List<String> legacyCaptured = new ArrayList<>();
        RocketmqTransactionFailureCallback legacyCallback = (topic, bizKey, payload, cause) ->
                legacyCaptured.add(topic + "|" + bizKey + "|" + payload + "|" + cause.getMessage());

        legacyCallback.onFailure("order_topic", "create", "ORD-404", "payload-4", "trace-4", rootCause);
        assertThat(legacyCaptured).containsExactly("order_topic|ORD-404|payload-4|Broker unavailable");

        List<String> sixParamCaptured = new ArrayList<>();
        RocketmqTransactionFailureCallback modernCallback = new RocketmqTransactionFailureCallback() {
            @Override
            public void onFailure(String topic, String bizKey, Object payload, Throwable cause) {
                throw new AssertionError("Should invoke 6-param override directly");
            }

            @Override
            public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
                sixParamCaptured.add(topic + "|" + tag + "|" + bizKey + "|" + payload + "|" + traceId + "|" + cause.getMessage());
            }
        };

        modernCallback.onFailure("order_topic", "pay", "ORD-500", "payload-6", "trace-6", rootCause);
        assertThat(sixParamCaptured).containsExactly("order_topic|pay|ORD-500|payload-6|trace-6|Broker unavailable");
    }

    @Test
    @DisplayName("验证 sendAfterCommit 四维上下文固化与还原：注册期快照隔离后置线程变更并透传至 6 参失败回调")
    void testSendAfterCommitCapturesAndRestores4DContext() throws Exception {
        AtomicBoolean sixParamCalled = new AtomicBoolean(false);
        List<String> observedCallbackContext = new ArrayList<>();
        RocketmqTransactionFailureCallback modernCallback = new RocketmqTransactionFailureCallback() {
            @Override
            public void onFailure(String topic, String bizKey, Object payload, Throwable cause) {
                throw new AssertionError("Should invoke 6-param overload");
            }

            @Override
            public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
                sixParamCalled.set(true);
                observedCallbackContext.add(String.join("|",
                        topic,
                        String.valueOf(tag),
                        bizKey,
                        String.valueOf(traceId),
                        String.valueOf(TenantContextHolder.getTenantId()),
                        String.valueOf(UserContextHolder.getUserId()),
                        String.valueOf(TenantContextHolder.isIgnoreTenant())));
            }
        };

        RocketmqTemplate txTemplate = new RocketmqTemplate(producer, properties, modernCallback);

        // 注册时刻设置完整四维上下文
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-reg-001");
        TenantContextHolder.setTenantId("tenant-reg-01");
        UserContextHolder.setUserId("user-reg-01");
        TenantContextHolder.setIgnoreTenant(true);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // 1. 合法消息注册后置发送，捕获注册期上下文
            txTemplate.sendAfterCommit("tx_ctx_topic", "create", "BIZ-CTX-1", "ok-payload", null);

            // 2. 非法载荷前置 Fail-Fast：不得新增同步器
            int syncCountBeforeInvalid = TransactionSynchronizationManager.getSynchronizations().size();
            assertThatThrownBy(() -> txTemplate.sendAfterCommit("tx_ctx_topic", "create", "BIZ-NULL", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(syncCountBeforeInvalid);

            // 3. 模拟注册之后、commit 之前线程上下文被污染或篡改
            MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-other-999");
            TenantContextHolder.setTenantId("tenant-other-99");
            UserContextHolder.setUserId("user-other-99");
            TenantContextHolder.setIgnoreTenant(false);

            // 触发成功发送并校验报文四维头来自注册期
            when(producer.send(any(Message.class)))
                    .thenReturn(mockReceipt)
                    .thenThrow(new RuntimeException("Simulated commit send failure"));

            TransactionSynchronization firstSync = TransactionSynchronizationManager.getSynchronizations().get(0);
            firstSync.afterCommit();

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(producer, times(1)).send(captor.capture());
            Map<String, String> sentProps = captor.getValue().getProperties();
            assertThat(sentProps).containsEntry(MessageHeaderConstants.TRACE_ID, "trace-reg-001");
            assertThat(sentProps).containsEntry(MessageHeaderConstants.TENANT_ID, "tenant-reg-01");
            assertThat(sentProps).containsEntry(MessageHeaderConstants.USER_ID, "user-reg-01");
            assertThat(sentProps).containsEntry(MessageHeaderConstants.IGNORE_TENANT, "true");

            // 严格验证 finally 还原为 commit 线程原有上下文（未残留注册期快照）
            assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("trace-other-999");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-other-99");
            assertThat(UserContextHolder.getUserId()).isEqualTo("user-other-99");
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();

            // 4. 再注册一条失败消息并在清空上下文环境下触发 afterCommit，校验 6 参回调与 finally 清空
            MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-reg-fail");
            TenantContextHolder.setTenantId("tenant-reg-fail");
            UserContextHolder.setUserId("user-reg-fail");
            TenantContextHolder.setIgnoreTenant(true);
            txTemplate.sendAfterCommit("tx_ctx_topic", "failTag", "BIZ-CTX-FAIL", "fail-payload", null);

            MDC.clear();
            TenantContextHolder.clear();
            UserContextHolder.clear();

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            syncs.get(syncs.size() - 1).afterCommit();

            assertThat(sixParamCalled).isTrue();
            assertThat(observedCallbackContext)
                    .containsExactly("tx_ctx_topic|failTag|BIZ-CTX-FAIL|trace-reg-fail|tenant-reg-fail|user-reg-fail|true");
            assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.getUserId()).isNull();
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证 sendBatchAfterCommit 前置检验与空批处理：空批零同步器、非法元素/超限批同步 Fail-Fast")
    void testSendBatchAfterCommitPreValidationAndEmptyList() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // 空集或 null 不抛异常且不挂载冗余同步器
            rocketmqTemplate.sendBatchAfterCommit("batch_tx_topic", Collections.emptyList());
            rocketmqTemplate.sendBatchAfterCommit("batch_tx_topic", "tagA", (List<?>) null, Map.of("k", "v"));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

            // 含 null 元素立即 Fail-Fast，零同步器注册、零底层发送
            List<Object> containsNull = new ArrayList<>();
            containsNull.add("ok");
            containsNull.add(null);
            assertThatThrownBy(() -> rocketmqTemplate.sendBatchAfterCommit("batch_tx_topic", "tagA", containsNull))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("index=1");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

            // 超出 maxBatchSize 立即 Fail-Fast，零同步器注册
            AtengRocketmqProperties tightProps = new AtengRocketmqProperties();
            tightProps.getProducer().setMaxBatchSize(2);
            RocketmqTemplate tightTemplate = new RocketmqTemplate(producer, tightProps);
            assertThatThrownBy(() -> tightTemplate.sendBatchAfterCommit("batch_tx_topic", List.of("a", "b", "c")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出配置上限");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(producer, never()).send(any(Message.class));
            verify(producer, never()).sendAsync(any(Message.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证 sendBatchAfterCommit 部分失败隔离与 4D 上下文还原：单条失败与回调异常不阻断同批后续投递")
    void testSendBatchAfterCommitPartialFailureIsolationAndContextRestore() throws Exception {
        List<String> failedBizKeys = new ArrayList<>();
        RocketmqTransactionFailureCallback flakyCallback = new RocketmqTransactionFailureCallback() {
            @Override
            public void onFailure(String topic, String bizKey, Object payload, Throwable cause) {
                throw new AssertionError("Should call 6-param overload");
            }

            @Override
            public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
                failedBizKeys.add(topic + "|" + tag + "|" + bizKey + "|" + traceId);
                // 模拟回调自身抛异常，验证隔离不影响后续同批消息投递
                throw new IllegalStateException("Callback persistence error");
            }
        };

        RocketmqTemplate txTemplate = new RocketmqTemplate(producer, properties, flakyCallback);

        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-batch-reg");
        TenantContextHolder.setTenantId("tenant-batch-01");
        UserContextHolder.setUserId("user-batch-01");
        TenantContextHolder.setIgnoreTenant(true);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            List<TestBatchItem> payloads = List.of(
                    new TestBatchItem("BATCH-TX-1"),
                    new TestBatchItem("BATCH-TX-2"),
                    new TestBatchItem("BATCH-TX-3")
            );
            txTemplate.sendBatchAfterCommit(
                    "batch_tx_topic",
                    "batch",
                    payloads,
                    Map.of("region", "cn-east", MessageHeaderConstants.TRACE_ID, "hacked"));

            verify(producer, never()).send(any(Message.class));

            // 注册期后修改当前线程上下文
            MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-caller-after");
            TenantContextHolder.setTenantId("tenant-caller-after");
            UserContextHolder.setUserId("user-caller-after");
            TenantContextHolder.setIgnoreTenant(false);

            // 第 2 条抛异常，第 1 条与第 3 条成功
            when(producer.send(any(Message.class)))
                    .thenReturn(mockReceipt)
                    .thenThrow(new RuntimeException("Item 2 network timeout"))
                    .thenReturn(mockReceipt);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(producer, times(3)).send(captor.capture());

            List<Message> sentMessages = captor.getAllValues();
            assertThat(sentMessages).hasSize(3);
            assertThat(sentMessages.get(0).getProperties())
                    .containsEntry("region", "cn-east")
                    .containsEntry(MessageHeaderConstants.TRACE_ID, "trace-batch-reg")
                    .containsEntry(MessageHeaderConstants.TENANT_ID, "tenant-batch-01")
                    .containsEntry(MessageHeaderConstants.USER_ID, "user-batch-01")
                    .containsEntry(MessageHeaderConstants.IGNORE_TENANT, "true");

            // 验证第 2 条触发 6 参回调，第 3 条未被回调异常阻断
            assertThat(failedBizKeys).containsExactly("batch_tx_topic|batch|BATCH-TX-2|trace-batch-reg");

            // 验证 finally 严格还原 commit 线程原始上下文
            assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("trace-caller-after");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-caller-after");
            assertThat(UserContextHolder.getUserId()).isEqualTo("user-caller-after");
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证 sendBatchAfterCommit 无活跃事务时立即批量发送")
    void testSendBatchAfterCommitWithoutTransaction() {
        rocketmqTemplate.sendBatchAfterCommit("batch_tx_no_tx", List.of("m1", "m2"), Map.of("scene", "direct"));
        verify(producer, times(2)).sendAsync(any(Message.class));
    }

    public record TestBatchItem(String id) {
        public String getId() {
            return id;
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
    @DisplayName("验证批量发送：空集与 null 列表保底返回空不可变集合且零底层发送")
    void testSendBatchAndAsyncEmptyPayloadsReturnImmutableEmptyList() throws Exception {
        List<SendReceipt> syncEmpty = rocketmqTemplate.sendBatch("batch_order_topic", Collections.emptyList());
        List<SendReceipt> syncNull = rocketmqTemplate.sendBatch("batch_order_topic", "batch", null);
        List<SendReceipt> asyncEmpty = rocketmqTemplate.sendBatchAsync("batch_order_topic", Collections.emptyList()).get();
        List<SendReceipt> asyncNull = rocketmqTemplate.sendBatchAsync("batch_order_topic", "batch", null, Map.of("k", "v")).get();

        assertThat(syncEmpty).isEmpty();
        assertThat(syncNull).isEmpty();
        assertThat(asyncEmpty).isEmpty();
        assertThat(asyncNull).isEmpty();
        assertThatThrownBy(() -> syncEmpty.add(mockReceipt)).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("验证批量发送：超出 maxBatchSize 上限触发 Fail-Fast 且零底层发送")
    void testSendBatchExceedsMaxBatchSizeThrowsException() {
        List<String> largeBatch = new ArrayList<>(501);
        for (int i = 0; i < 501; i++) {
            largeBatch.add("item-" + i);
        }

        assertThatThrownBy(() -> rocketmqTemplate.sendBatch("batch_order_topic", largeBatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出配置上限 [500]");
        verify(producer, never()).sendAsync(any(Message.class));
    }

    @Test
    @DisplayName("验证批量发送前置全量校验：含 null 元素或压缩后超限报文在触发任何发送前 Fail-Fast")
    void testSendBatchFailFastOnNullElementOrOversizedPayloadWithoutAnySend() {
        List<Object> containsNull = new ArrayList<>();
        containsNull.add("valid-1");
        containsNull.add("valid-2");
        containsNull.add(null);

        assertThatThrownBy(() -> rocketmqTemplate.sendBatch("batch_order_topic", containsNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        assertThatThrownBy(() -> rocketmqTemplate.sendBatchAsync("batch_order_topic", "batch", containsNull, Map.of("k", "v")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(producer, never()).sendAsync(any(Message.class));

        properties.getProducer().setCompressionThreshold(DataSize.ofBytes(64));
        properties.getProducer().setMaxPayloadSize(DataSize.ofBytes(120));

        // 超过 maxPayloadSize（120B）且末尾条目超限，验证整批在零发送下 Fail-Fast
        byte[] oversizedPayload = new byte[512];
        new java.util.Random(42L).nextBytes(oversizedPayload);
        List<Object> oversizedBatch = List.of("ok-1", "ok-2", oversizedPayload);

        assertThatThrownBy(() -> rocketmqTemplate.sendBatch("batch_order_topic", "batch", oversizedBatch))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("超过配置上限");
        verify(producer, never()).sendAsync(any(Message.class));

        // 超过 GZIP 压缩阈值 (64B) 且 <= maxPayloadSize (120B) 的合法批次正常压缩并完成投递
        String validCompressedBatchItem = "OK-COMP-".repeat(12);
        List<SendReceipt> compressedReceipts = rocketmqTemplate.sendBatch("batch_order_topic", List.of(validCompressedBatchItem));
        assertThat(compressedReceipts).hasSize(1);
    }

    @Test
    @DisplayName("验证批量双层分片并发发送：超 batchSliceSize(64) 按分片调度并保序返回全部回执")
    void testSendBatchSlicesAbove64AndPreservesReceiptOrder() throws Exception {
        int total = 130;
        List<String> payloads = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            payloads.add("order-payload-" + i);
        }

        AtomicInteger sliceDispatches = new AtomicInteger(0);
        java.util.concurrent.Executor countingExecutor = cmd -> {
            sliceDispatches.incrementAndGet();
            CompletableFuture.runAsync(cmd);
        };
        RocketmqTemplate slicedTemplate = new RocketmqTemplate(
                producer, properties, (RocketmqTransactionFailureCallback) null, countingExecutor);

        when(producer.sendAsync(any(Message.class))).thenAnswer(inv -> {
            Message msg = inv.getArgument(0);
            byte[] bytes = new byte[msg.getBody().remaining()];
            msg.getBody().duplicate().get(bytes);
            String payloadText = new String(bytes, StandardCharsets.UTF_8);

            SendReceipt receipt = mock(SendReceipt.class);
            MessageId id = mock(MessageId.class);
            when(id.toString()).thenReturn("ID-" + payloadText);
            when(receipt.getMessageId()).thenReturn(id);
            return CompletableFuture.completedFuture(receipt);
        });

        Map<String, String> userProperties = Map.of("region", "cn-east", MessageHeaderConstants.TRACE_ID, "hacked");
        List<SendReceipt> receipts = slicedTemplate.sendBatch(
                "batch_order_topic", "batch", payloads, Duration.ofSeconds(5), userProperties);

        // 130 条按默认 batchSliceSize=64 切分为 64 + 64 + 2 = 3 个分片并发调度
        assertThat(sliceDispatches.get()).isEqualTo(3);
        assertThat(receipts).hasSize(total);
        for (int i = 0; i < total; i++) {
            assertThat(receipts.get(i).getMessageId().toString()).isEqualTo("ID-order-payload-" + i);
        }

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(total)).sendAsync(captor.capture());
        assertThat(captor.getValue().getProperties()).containsEntry("region", "cn-east");
        assertThat(captor.getValue().getProperties()).containsEntry(MessageHeaderConstants.TRACE_ID, "trace-test-888");
    }

    @Test
    @DisplayName("验证批量发送：异步批量返回 CompletableFuture（含 userProperties 透传）")
    void testSendBatchAsyncSuccess() throws Exception {
        List<String> payloads = List.of("msg1", "msg2");
        CompletableFuture<List<SendReceipt>> future = rocketmqTemplate.sendBatchAsync(
                "batch_order_topic", "batch", payloads, Map.of("channel", "app"));

        List<SendReceipt> receipts = future.get();
        assertThat(receipts).hasSize(2);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(2)).sendAsync(captor.capture());
        assertThat(captor.getValue().getProperties()).containsEntry("channel", "app");
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

    @Test
    @DisplayName("验证异步批量发送异常转译：失败 Future 必须转译为 MessageDeliveryException 并保留原始 cause")
    void testSendBatchAsyncTranslatesFailureToMessageDeliveryException() {
        RuntimeException rootCause = new RuntimeException("Async broker failure");
        when(producer.sendAsync(any(Message.class)))
                .thenReturn(CompletableFuture.failedFuture(rootCause));

        CompletableFuture<List<SendReceipt>> future = rocketmqTemplate.sendBatchAsync(
                "batch_async_fail_topic", "batch", List.of("item1", "item2"), Map.of("region", "cn-west"));

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(MessageDeliveryException.class)
                .hasRootCauseMessage("Async broker failure");
    }

    @Test
    @DisplayName("验证批量发送超时场景：超出指定 Duration 抛出携带 TimeoutException cause 的 MessageDeliveryException")
    void testSendBatchTimeoutThrowsMessageDeliveryException() {
        when(producer.sendAsync(any(Message.class)))
                .thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> rocketmqTemplate.sendBatch(
                "batch_timeout_topic", "tag", List.of("item1"), Duration.ofMillis(50), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("验证延时发送参数防卫：null 或负数 Duration 触发 Fail-Fast")
    void testSendDelayedRejectsNullOrNegativeDuration() {
        assertThatThrownBy(() -> rocketmqTemplate.sendDelayed("delay_topic", "payload", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delay 不能为空");

        assertThatThrownBy(() -> rocketmqTemplate.sendDelayed("delay_topic", "payload", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delay 延时时长不能为负数");
    }

    @Test
    @DisplayName("验证系统级与保留前缀 userProperties 拦截：大小写不敏感且不污染上下文头")
    void testProtectedHeaderKeysAreStripped() throws ClientException {
        MDC.put("traceId", "trace-real-001");
        Map<String, String> dirtyProps = new HashMap<>();
        dirtyProps.put("X-User-Id", "spoofed-user");
        dirtyProps.put("X-Ignore-Tenant", "true");
        dirtyProps.put("X-Send-Timestamp", "999999");
        dirtyProps.put("x-trace-id", "spoofed-trace");
        dirtyProps.put("%RETRY%order_group", "1");
        dirtyProps.put("%dlq%order_group", "1");
        dirtyProps.put("retry_topic", "hacked_retry");
        dirtyProps.put("env", "prod");

        rocketmqTemplate.sendDelayed("delay_topic", "delay", "ORD-HEADER-01", "payload", Duration.ofSeconds(5), dirtyProps);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Map<String, String> props = captor.getValue().getProperties();
        assertThat(props).containsEntry("env", "prod");
        assertThat(props).containsEntry(MessageHeaderConstants.TRACE_ID, "trace-real-001");
        assertThat(props.get(MessageHeaderConstants.SEND_TIMESTAMP)).isNotEqualTo("999999");
        assertThat(props).doesNotContainKeys(
                "X-User-Id", "X-Ignore-Tenant", "x-trace-id",
                "%RETRY%order_group", "%dlq%order_group", "retry_topic");
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
