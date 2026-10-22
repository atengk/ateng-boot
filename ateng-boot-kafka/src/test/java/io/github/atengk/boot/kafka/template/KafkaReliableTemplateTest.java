package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * KafkaReliableTemplate 生产端增强门面单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("KafkaReliableTemplate 生产增强门面单元测试")
class KafkaReliableTemplateTest {

    private KafkaTemplate<Object, Object> kafkaTemplate;
    private AtengKafkaProperties properties;
    private KafkaTransactionFailureCallback failureCallback;
    private KafkaReliableTemplate template;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new AtengKafkaProperties();
        failureCallback = mock(KafkaTransactionFailureCallback.class);
        template = new KafkaReliableTemplate(kafkaTemplate, properties, failureCallback);

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("同步可靠发送：成功投递并返回 SendResult，自动注入四维上下文与 bizKey")
    @SuppressWarnings("unchecked")
    void testSendReliableSuccess() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-1001");
        TenantContextHolder.setTenantId("tenant-888");
        UserContextHolder.setUserId("user-999");

        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);

        doAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 1), 100L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        }).when(kafkaTemplate).send(recordCaptor.capture());

        SendResult<Object, Object> result = template.sendReliable("test.order.topic", "order-123", "order-payload", "biz-key-001");

        assertThat(result).isNotNull();
        assertThat(result.getRecordMetadata().partition()).isEqualTo(1);
        assertThat(result.getRecordMetadata().offset()).isEqualTo(100L);

        ProducerRecord<Object, Object> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("test.order.topic");
        assertThat(record.key()).isEqualTo("order-123");
        assertThat(record.value()).isEqualTo("order-payload");

        // 验证 Kafka Headers 四维上下文注入
        Header traceHeader = record.headers().lastHeader(MessageHeaderConstants.TRACE_ID);
        Header tenantHeader = record.headers().lastHeader(MessageHeaderConstants.TENANT_ID);
        Header userHeader = record.headers().lastHeader(MessageHeaderConstants.USER_ID);
        Header bizHeader = record.headers().lastHeader(MessageHeaderConstants.BIZ_KEY);

        assertThat(new String(traceHeader.value(), StandardCharsets.UTF_8)).isEqualTo("trace-1001");
        assertThat(new String(tenantHeader.value(), StandardCharsets.UTF_8)).isEqualTo("tenant-888");
        assertThat(new String(userHeader.value(), StandardCharsets.UTF_8)).isEqualTo("user-999");
        assertThat(new String(bizHeader.value(), StandardCharsets.UTF_8)).isEqualTo("biz-key-001");
    }

    @Test
    @DisplayName("同步可靠发送：显式指定物理分区 partition")
    @SuppressWarnings("unchecked")
    void testSendReliableWithPartition() {
        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);

        doAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), rec.partition()), 0L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        }).when(kafkaTemplate).send(recordCaptor.capture());

        SendResult<Object, Object> result = template.sendReliable("test.partition.topic", 2, "key-2", "val-2");

        assertThat(result.getRecordMetadata().partition()).isEqualTo(2);
        assertThat(recordCaptor.getValue().partition()).isEqualTo(2);
    }

    @Test
    @DisplayName("同步发送超时：等待确认超时抛出 MessageDeliveryException")
    void testSendReliableTimeoutThrowsException() {
        CompletableFuture<SendResult<Object, Object>> hungFuture = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(hungFuture);

        assertThatThrownBy(() -> template.sendReliable("test.timeout.topic", "key-t", "val-t", "biz-t", Duration.ofMillis(50)))
                .isInstanceOf(MessageDeliveryException.class)
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("kafka");
                    assertThat(mde.getTopic()).isEqualTo("test.timeout.topic");
                    assertThat(mde.getBizKey()).isEqualTo("biz-t");
                    assertThat(mde.getMessage()).contains("Kafka 同步发送确认超时");
                    assertThat(mde.getCause()).isInstanceOf(java.util.concurrent.TimeoutException.class);
                });
    }

    @Test
    @DisplayName("同步发送底层异常：Broker 报错转译为 MessageDeliveryException (INTERNAL_SERVER_ERROR)")
    void testSendReliableErrorThrowsException() {
        CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker disconnected"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        assertThatThrownBy(() -> template.sendReliable("test.error.topic", "key-e", "val-e", "biz-e"))
                .isInstanceOf(MessageDeliveryException.class)
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("kafka");
                    assertThat(mde.getTopic()).isEqualTo("test.error.topic");
                    assertThat(mde.getBizKey()).isEqualTo("biz-e");
                    assertThat(mde.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
                    assertThat(mde.getCause()).hasMessageContaining("Kafka broker disconnected");
                });
    }

    @Test
    @DisplayName("大报文前置防御：超过 maxPayloadSize 报文在发信前 Fail-Fast 阻断，零网络调用")
    void testMaxPayloadSizeEnforcement() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(10)); // 临时设为 10KB 方便快速单测

        byte[] largeBytes = new byte[15 * 1024]; // 15KB > 10KB

        assertThatThrownBy(() -> template.sendReliable("test.large.topic", "k-large", largeBytes, "biz-large"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getResultCode()).isEqualTo(CommonResultCode.BAD_REQUEST);
                });

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("异步可靠发送：返回 CompletableFuture 并在确认时正确完成")
    void testSendReliableAsync() {
        ProducerRecord<Object, Object> mockRecord = new ProducerRecord<>("async.topic", "ak", "av");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("async.topic", 0), 50L, 0, System.currentTimeMillis(), 0, 0);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(mockRecord, metadata)));

        CompletableFuture<SendResult<Object, Object>> future = template.sendReliableAsync("async.topic", "ak", "av", "biz-async");

        assertThat(future).isCompleted();
        SendResult<Object, Object> result = future.join();
        assertThat(result.getRecordMetadata().offset()).isEqualTo(50L);
    }

    @Test
    @DisplayName("sendAfterCommit 无事务场景：直接立即同步发送")
    void testSendAfterCommitWithoutTransaction() {
        ProducerRecord<Object, Object> mockRecord = new ProducerRecord<>("tx.topic", "tk", "tv");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("tx.topic", 0), 1L, 0, System.currentTimeMillis(), 0, 0);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(mockRecord, metadata)));

        template.sendAfterCommit("tx.topic", "tk", "tv", "biz-no-tx");

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("sendAfterCommit 有活跃事务：等待 afterCommit 提交后才物理发信，回滚时绝对零发信")
    void testSendAfterCommitWithActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        ProducerRecord<Object, Object> mockRecord = new ProducerRecord<>("tx.topic", "tk", "tv");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("tx.topic", 0), 1L, 0, System.currentTimeMillis(), 0, 0);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(mockRecord, metadata)));

        // 1. 调用 sendAfterCommit
        template.sendAfterCommit("tx.topic", "tk", "tv", "biz-tx-1");

        // 验证此时尚未物理发信
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        // 2. 模拟本地事务 Commit 成功触发 afterCommit
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.get(0).afterCommit();

        // 验证 afterCommit 执行后真正触发物理发信
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("sendAfterCommit 事务提交后发信失败：触发高危告警并自动回调 KafkaTransactionFailureCallback SPI")
    void testSendAfterCommitFailureInvokesCallback() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Post-commit kafka timeout"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        template.sendAfterCommit("tx.topic", "tk", "tv", "biz-tx-fail");

        // 触发 afterCommit
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.get(0).afterCommit();

        // 验证失败回调被触发
        verify(failureCallback, times(1)).onFailure(
                eq("tx.topic"),
                isNull(),
                eq("tk"),
                eq("tv"),
                eq("biz-tx-fail"),
                any(),
                any(Throwable.class)
        );
    }

    @Test
    @DisplayName("批量发送：支持 Map 批量发信并返回批量确认结果")
    void testSendBatch() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 1L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        Map<String, Object> batchMap = new HashMap<>();
        batchMap.put("k1", "v1");
        batchMap.put("k2", "v2");
        batchMap.put("k3", "v3");

        List<SendResult<Object, Object>> results = template.sendBatch("batch.topic", batchMap);

        assertThat(results).hasSize(3);
        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("批量发送：支持 List 载荷批量发信及同 Key 分区保序批量投递")
    @SuppressWarnings("unchecked")
    void testSendBatchListAndWithKey() {
        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture())).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 1L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        // 1. 无指定 key 的 List 批量发送
        List<String> listPayloads = List.of("m1", "m2");
        List<SendResult<Object, Object>> listResults = template.sendBatch("batch.list.topic", listPayloads);
        assertThat(listResults).hasSize(2);

        // 2. 指定同 key 的 List 批量发送（同一分区保序）
        List<String> orderEvents = List.of("CREATED", "PAID", "SHIPPED");
        List<SendResult<Object, Object>> keyedResults = template.sendBatch("batch.order.topic", "order-8888", orderEvents);
        assertThat(keyedResults).hasSize(3);

        List<ProducerRecord<Object, Object>> records = recordCaptor.getAllValues();
        // 后面 3 条 record 的 key 必须严格等于 "order-8888"
        assertThat(records.subList(2, 5)).allMatch(rec -> "order-8888".equals(rec.key()));
    }

    @Test
    @DisplayName("MDC TraceId 兜底业务键：当未显式指定 bizKey 且实体无 ID 时，优先使用 MDC traceId 兜底")
    @SuppressWarnings("unchecked")
    void testResolveBizKeyFallbackToMdcTraceId() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-fixed-888");

        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture())).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 0L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        // 发送纯文本消息，未传 key，未传 bizKey
        template.sendReliable("test.bizkey.topic", "raw-content-message");

        ProducerRecord<Object, Object> record = recordCaptor.getValue();
        Header bizHeader = record.headers().lastHeader(MessageHeaderConstants.BIZ_KEY);
        assertThat(bizHeader).isNotNull();
        // bizKey 应当命中 MDC traceId 兜底逻辑
        assertThat(new String(bizHeader.value(), StandardCharsets.UTF_8)).isEqualTo("trace-fixed-888");
    }

    @Test
    @DisplayName("sendBatchRecords 大报文防御与上下文加固：超限时 Fail-Fast 阻断，正常时补全四维上下文")
    @SuppressWarnings("unchecked")
    void testSendBatchRecordsDefenses() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(5)); // 5KB 限制

        // 1. 构造一条超限报文 (10KB > 5KB)
        ProducerRecord<Object, Object> oversizedRecord = new ProducerRecord<>("batch.record.topic", "key1", new byte[10 * 1024]);
        assertThatThrownBy(() -> template.sendBatchRecords(List.of(oversizedRecord)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        // 2. 构造正常报文，未预置 headers，验证上下文自动补全
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-auto-ctx");
        TenantContextHolder.setTenantId("tenant-auto");

        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture())).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 0L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        ProducerRecord<Object, Object> normalRecord = new ProducerRecord<>("batch.record.topic", "key-norm", "norm-payload");
        List<SendResult<Object, Object>> results = template.sendBatchRecords(List.of(normalRecord));

        assertThat(results).hasSize(1);
        ProducerRecord<Object, Object> sent = recordCaptor.getValue();
        Header traceHeader = sent.headers().lastHeader(MessageHeaderConstants.TRACE_ID);
        Header tenantHeader = sent.headers().lastHeader(MessageHeaderConstants.TENANT_ID);
        assertThat(traceHeader).isNotNull();
        assertThat(new String(traceHeader.value(), StandardCharsets.UTF_8)).isEqualTo("trace-auto-ctx");
        assertThat(tenantHeader).isNotNull();
        assertThat(new String(tenantHeader.value(), StandardCharsets.UTF_8)).isEqualTo("tenant-auto");
    }
}
