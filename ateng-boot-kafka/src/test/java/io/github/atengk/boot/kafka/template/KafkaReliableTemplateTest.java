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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        TransactionSynchronizationManager.clear();
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

    @Test
    @DisplayName("自定义消息头重载：安全注入业务头，防御 null/空映射/空白键并禁止覆盖系统头")
    @SuppressWarnings("unchecked")
    void testSendWithCustomHeadersOverloads() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-hdr-001");
        TenantContextHolder.setTenantId("tenant-hdr-001");
        UserContextHolder.setUserId("user-hdr-001");

        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture())).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 1L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        Map<String, String> dirtyHeaders = new HashMap<>();
        dirtyHeaders.put("X-Event-Type", "ORDER_PAID");
        dirtyHeaders.put("  X-Source-App  ", "checkout-svc");
        dirtyHeaders.put("", "ignored-empty-key");
        dirtyHeaders.put("   ", "ignored-blank-key");
        dirtyHeaders.put(null, "ignored-null-key");
        dirtyHeaders.put("X-Null-Val", null);
        // 尝试伪造或覆盖框架系统头（含大小写变体），必须被防御拦截
        dirtyHeaders.put(MessageHeaderConstants.TRACE_ID, "spoofed-trace");
        dirtyHeaders.put("x-trace-id", "spoofed-trace-lower");
        dirtyHeaders.put(MessageHeaderConstants.TENANT_ID, "spoofed-tenant");
        dirtyHeaders.put(MessageHeaderConstants.USER_ID, "spoofed-user");
        dirtyHeaders.put(MessageHeaderConstants.IGNORE_TENANT, "true");
        dirtyHeaders.put(MessageHeaderConstants.BIZ_KEY, "spoofed-biz");

        // 1. 同步发送重载
        template.sendReliable("hdr.sync.topic", "k1", "payload-1", "biz-sync-1", dirtyHeaders);
        template.sendReliable("hdr.sync.topic", "k2", "payload-2", (Map<String, String>) null);
        template.sendReliable("hdr.sync.topic", 1, "k3", "payload-3", "biz-sync-3", Duration.ofSeconds(3), Map.of("X-Tag", "vip"));

        // 2. 异步发送重载
        template.sendReliableAsync("hdr.async.topic", "ak1", "async-1", dirtyHeaders).join();
        template.sendReliableAsync("hdr.async.topic", "ak2", "async-2", "biz-async-2", Map.of()).join();
        template.sendReliableAsync("hdr.async.topic", 2, "ak3", "async-3", "biz-async-3", Map.of("X-Async-Mode", "fast")).join();

        // 3. 事务提交后发信重载
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        template.sendAfterCommit("hdr.tx.topic", "tk1", "tx-1", dirtyHeaders);
        template.sendAfterCommit("hdr.tx.topic", "tk2", "tx-2", "biz-tx-2", dirtyHeaders);
        template.sendAfterCommit("hdr.tx.topic", 0, "tk3", "tx-3", "biz-tx-3", Duration.ofSeconds(2), Map.of("X-Tx-Tag", "atomic"));
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        List<ProducerRecord<Object, Object>> records = recordCaptor.getAllValues();
        assertThat(records).hasSize(9);

        // 验证第一条（sync + dirtyHeaders）
        ProducerRecord<Object, Object> r0 = records.get(0);
        assertThat(new String(r0.headers().lastHeader("X-Event-Type").value(), StandardCharsets.UTF_8)).isEqualTo("ORDER_PAID");
        assertThat(new String(r0.headers().lastHeader("X-Source-App").value(), StandardCharsets.UTF_8)).isEqualTo("checkout-svc");
        assertThat(r0.headers().lastHeader("")).isNull();
        assertThat(r0.headers().lastHeader("   ")).isNull();
        assertThat(r0.headers().lastHeader("X-Null-Val")).isNull();
        assertThat(new String(r0.headers().lastHeader(MessageHeaderConstants.TRACE_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-hdr-001");
        assertThat(r0.headers().lastHeader("x-trace-id")).isNull();
        assertThat(new String(r0.headers().lastHeader(MessageHeaderConstants.TENANT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("tenant-hdr-001");
        assertThat(new String(r0.headers().lastHeader(MessageHeaderConstants.USER_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("user-hdr-001");
        assertThat(r0.headers().lastHeader(MessageHeaderConstants.IGNORE_TENANT)).isNull();
        assertThat(new String(r0.headers().lastHeader(MessageHeaderConstants.BIZ_KEY).value(), StandardCharsets.UTF_8))
                .isEqualTo("biz-sync-1");

        // 验证异步与事务提交后发信携带了自定义头且保留系统头
        ProducerRecord<Object, Object> rAsync = records.get(5);
        assertThat(new String(rAsync.headers().lastHeader("X-Async-Mode").value(), StandardCharsets.UTF_8)).isEqualTo("fast");
        ProducerRecord<Object, Object> rTx = records.get(7);
        assertThat(new String(rTx.headers().lastHeader("X-Event-Type").value(), StandardCharsets.UTF_8)).isEqualTo("ORDER_PAID");
        assertThat(new String(rTx.headers().lastHeader(MessageHeaderConstants.BIZ_KEY).value(), StandardCharsets.UTF_8)).isEqualTo("biz-tx-2");
        ProducerRecord<Object, Object> rTx3 = records.get(8);
        assertThat(new String(rTx3.headers().lastHeader("X-Tx-Tag").value(), StandardCharsets.UTF_8)).isEqualTo("atomic");
    }

    @Test
    @DisplayName("两阶段批量：空集合均安全返回空列表且零网络调用")
    void testBatchMethodsEmptyCollectionsReturnEmptyAndNoSend() {
        assertThat(template.sendBatch("batch.empty.topic", Collections.emptyList())).isEmpty();
        assertThat(template.sendBatch("batch.empty.topic", "k", Collections.emptyList())).isEmpty();
        assertThat(template.sendBatch("batch.empty.topic", Collections.emptyMap())).isEmpty();
        assertThat(template.sendBatchRecords(Collections.emptyList())).isEmpty();

        assertThat(template.sendBatchAsync("batch.empty.topic", Collections.emptyList()).join()).isEmpty();
        assertThat(template.sendBatchAsync("batch.empty.topic", "k", Collections.emptyList()).join()).isEmpty();
        assertThat(template.sendBatchAsync("batch.empty.topic", Collections.emptyMap()).join()).isEmpty();
        assertThat(template.sendBatchRecordsAsync(Collections.emptyList()).join()).isEmpty();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("两阶段批量前置防卫：超 maxBatchSize、含 null 元素或后段报文超限时阶段 1 Fail-Fast 且零网络发送")
    void testTwoPhaseBatchFailFastZeroNetworkSend() {
        properties.getProducer().setMaxBatchSize(2);
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(4));

        // 1. 超过 maxBatchSize (3 > 2)
        List<String> tooManyPayloads = List.of("m1", "m2", "m3");
        assertThatThrownBy(() -> template.sendBatch("batch.limit.topic", tooManyPayloads))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");
        assertThatThrownBy(() -> template.sendBatchAsync("batch.limit.topic", tooManyPayloads))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");

        Map<String, Object> tooManyMap = new LinkedHashMap<>();
        tooManyMap.put("k1", "v1");
        tooManyMap.put("k2", "v2");
        tooManyMap.put("k3", "v3");
        assertThatThrownBy(() -> template.sendBatch("batch.limit.topic", tooManyMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");
        assertThatThrownBy(() -> template.sendBatchAsync("batch.limit.topic", tooManyMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");

        List<ProducerRecord<Object, Object>> tooManyRecords = List.of(
                new ProducerRecord<>("batch.limit.topic", "k1", "v1"),
                new ProducerRecord<>("batch.limit.topic", "k2", "v2"),
                new ProducerRecord<>("batch.limit.topic", "k3", "v3")
        );
        assertThatThrownBy(() -> template.sendBatchRecords(tooManyRecords))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");
        assertThatThrownBy(() -> template.sendBatchRecordsAsync(tooManyRecords))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");

        // 2. 列表中第 2 条为 null，前 1 条绝对禁止提前发送
        List<String> withNull = Arrays.asList("valid-1", null);
        assertThatThrownBy(() -> template.sendBatch("batch.null.topic", "k", withNull))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.sendBatchAsync("batch.null.topic", "k", withNull))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> mapWithNull = new LinkedHashMap<>();
        mapWithNull.put("k1", "valid-1");
        mapWithNull.put("k2", null);
        assertThatThrownBy(() -> template.sendBatch("batch.null.topic", mapWithNull))
                .isInstanceOf(IllegalArgumentException.class);

        List<ProducerRecord<Object, Object>> recordsWithNull = Arrays.asList(
                new ProducerRecord<>("batch.null.topic", "k1", "v1"),
                null
        );
        assertThatThrownBy(() -> template.sendBatchRecords(recordsWithNull))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.sendBatchRecordsAsync(recordsWithNull))
                .isInstanceOf(IllegalArgumentException.class);

        // 3. 第 1 条正常，第 2 条超出 maxPayloadSize (6KB > 4KB)，阶段 1 整批阻断且零发送
        List<Object> secondOversized = List.of("small-ok", new byte[6 * 1024]);
        assertThatThrownBy(() -> template.sendBatch("batch.oversize.topic", secondOversized))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");
        assertThatThrownBy(() -> template.sendBatchAsync("batch.oversize.topic", secondOversized))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        List<ProducerRecord<Object, Object>> recordsSecondOversized = List.of(
                new ProducerRecord<>("batch.oversize.topic", "k1", "small-ok"),
                new ProducerRecord<>("batch.oversize.topic", "k2", new byte[6 * 1024])
        );
        assertThatThrownBy(() -> template.sendBatchRecords(recordsSecondOversized))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");
        assertThatThrownBy(() -> template.sendBatchRecordsAsync(recordsSecondOversized))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("异步批量发送：sendBatchAsync 与 sendBatchRecordsAsync 支持四维上下文、自定义头注入与异常传播")
    @SuppressWarnings("unchecked")
    void testSendBatchAsyncAndRecordsAsyncSuccessAndFailurePropagation() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-batch-async");
        TenantContextHolder.setTenantId("tenant-batch-async");
        UserContextHolder.setUserId("user-batch-async");

        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture())).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 10L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        // 1. sendBatchAsync (List + key + customHeaders)
        List<SendResult<Object, Object>> listRes = template.sendBatchAsync(
                "async.batch.topic",
                "order-group-1",
                List.of("e1", "e2"),
                Map.of("X-Batch-Source", "orders-async", MessageHeaderConstants.TRACE_ID, "spoofed")
        ).join();
        assertThat(listRes).hasSize(2);

        // 2. sendBatchAsync (Map)
        Map<String, Object> keyMap = new LinkedHashMap<>();
        keyMap.put("k1", "v1");
        keyMap.put("k2", "v2");
        List<SendResult<Object, Object>> mapRes = template.sendBatchAsync("async.map.topic", keyMap).join();
        assertThat(mapRes).hasSize(2);

        // 3. sendBatchRecordsAsync
        List<ProducerRecord<Object, Object>> rawRecords = List.of(
                new ProducerRecord<>("async.rec.topic", "rk1", "rv1"),
                new ProducerRecord<>("async.rec.topic", "rk2", "rv2")
        );
        List<SendResult<Object, Object>> recRes = template.sendBatchRecordsAsync(rawRecords).join();
        assertThat(recRes).hasSize(2);

        List<ProducerRecord<Object, Object>> sent = recordCaptor.getAllValues();
        assertThat(sent).hasSize(6);
        assertThat(new String(sent.get(0).headers().lastHeader("X-Batch-Source").value(), StandardCharsets.UTF_8))
                .isEqualTo("orders-async");
        assertThat(new String(sent.get(0).headers().lastHeader(MessageHeaderConstants.TRACE_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-batch-async");
        assertThat(new String(sent.get(4).headers().lastHeader(MessageHeaderConstants.TENANT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("tenant-batch-async");

        // 4. 阶段 2 底层 Future 失败应通过聚合 Future 向外传播
        CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker batch write failed"));
        reset(kafkaTemplate);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        CompletableFuture<List<SendResult<Object, Object>>> asyncErrFuture =
                template.sendBatchAsync("async.err.topic", List.of("err-1"));
        assertThatThrownBy(asyncErrFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("broker batch write failed");
    }

    @Test
    @DisplayName("sendBatchAfterCommit 前置校验：违规 Fail-Fast 不注册事务同步器且零发送，空集合直接跳过")
    void testSendBatchAfterCommitFailFastBeforeSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        properties.getProducer().setMaxBatchSize(2);
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(2));

        // 1. 空集合不注册同步器，零发送
        template.sendBatchAfterCommit("tx.batch.topic", Collections.emptyList());
        template.sendBatchAfterCommit("tx.batch.topic", Collections.emptyMap());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

        // 2. 超 maxBatchSize / 含 null / 超 maxPayloadSize 立即抛错且不注册同步器
        assertThatThrownBy(() -> template.sendBatchAfterCommit("tx.batch.topic", List.of("1", "2", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出最大限制");
        assertThatThrownBy(() -> template.sendBatchAfterCommit("tx.batch.topic", "k", Arrays.asList("1", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.sendBatchAfterCommit("tx.batch.topic", List.of("ok", new byte[4 * 1024])))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("sendBatchAfterCommit 四维上下文捕获恢复与 finally 确定性清理")
    @SuppressWarnings("unchecked")
    void testSendBatchAfterCommitRestores4DContextAndCleansUpInFinally() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-tx-batch-001");
        UserContextHolder.setUserId("user-tx-batch-001");
        TenantContextHolder.setTenantId("tenant-tx-batch-001");
        TenantContextHolder.setIgnoreTenant(true);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        template.sendBatchAfterCommit(
                "tx.ctx.topic",
                "order-key",
                List.of("msg-1", "msg-2"),
                Duration.ofSeconds(3),
                Map.of("X-Tx-Batch", "v1")
        );

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        // 模拟提交回调执行前线程上下文被清空或切换为其他值
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        List<String> observedTraces = new ArrayList<>();
        List<String> observedUsers = new ArrayList<>();
        List<String> observedTenants = new ArrayList<>();
        List<Boolean> observedIgnoreTenants = new ArrayList<>();

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            observedTraces.add(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            observedUsers.add(UserContextHolder.getUserId());
            observedTenants.add(TenantContextHolder.getTenantId());
            observedIgnoreTenants.add(TenantContextHolder.isIgnoreTenant());
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 1L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        syncs.get(0).afterCommit();

        // 验证发送期四维上下文完整恢复
        assertThat(observedTraces).containsExactly("trace-tx-batch-001", "trace-tx-batch-001");
        assertThat(observedUsers).containsExactly("user-tx-batch-001", "user-tx-batch-001");
        assertThat(observedTenants).containsExactly("tenant-tx-batch-001", "tenant-tx-batch-001");
        assertThat(observedIgnoreTenants).containsExactly(true, true);

        // 验证 finally 块确定性清理现场，不残留事务内上下文
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
    }

    @Test
    @DisplayName("sendBatchAfterCommit 部分记录发送失败：逐条精准回调 SPI，其余记录继续投递，无事务立即同步发送")
    @SuppressWarnings("unchecked")
    void testSendBatchAfterCommitPartialFailurePerRecordCallbackAndNonTxMode() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-partial-tx");
        TenantContextHolder.setTenantId("tenant-partial");
        UserContextHolder.setUserId("user-partial");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Map<String, Object> txMap = new LinkedHashMap<>();
        txMap.put("k1", "payload-1");
        txMap.put("k2", "payload-2");
        txMap.put("k3", "payload-3");
        template.sendBatchAfterCommit("tx.partial.topic", txMap, Duration.ofSeconds(2));

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);

        // 清理线程上下文以验证回调时恢复、回调后清理
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            if ("k2".equals(rec.key())) {
                CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("k2 broker timeout"));
                return failed;
            }
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 1L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        AtomicReference<String> callbackTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            callbackTenant.set(TenantContextHolder.getTenantId());
            return null;
        }).when(failureCallback).onFailure(anyString(), any(), any(), any(), any(), any(), any());

        syncs.get(0).afterCommit();

        // 3 条全部尝试发送，k2 失败不阻断 k3
        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
        // 仅针对 k2 精准回调 1 次
        verify(failureCallback, times(1)).onFailure(
                eq("tx.partial.topic"),
                isNull(),
                eq("k2"),
                eq("payload-2"),
                eq("k2"),
                eq("trace-partial-tx"),
                argThat(ex -> ex != null && ex.getMessage().contains("k2 broker timeout"))
        );
        verify(failureCallback, times(1)).onFailure(any(), any(), any(), any(), any(), any(), any());
        assertThat(callbackTenant.get()).isEqualTo("tenant-partial");
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 无活跃事务场景：立即同步发送
        TransactionSynchronizationManager.clear();
        template.sendBatchAfterCommit("notx.batch.topic", List.of("d1", "d2"));
        verify(kafkaTemplate, times(5)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("sendBatchRecordsAfterCommit 与边界对抗：Fail-Fast 零头注入副作用、非正超时校验、外层上下文还原及同步SDK异常转译")
    @SuppressWarnings("unchecked")
    void testSendBatchRecordsAfterCommitAndAdversarialGuards() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // 1. 空列表不注册同步器也不发送
        template.sendBatchRecordsAfterCommit(Collections.emptyList());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

        // 2. 非正超时时间在阶段 1 立即 Fail-Fast（零注册、零发送）
        assertThatThrownBy(() -> template.sendBatch("tx.t", List.of("a"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> template.sendBatchAfterCommit("tx.t", List.of("a"), Duration.ofMillis(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> template.sendBatchRecordsAfterCommit(Collections.emptyList(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

        // 3. sendBatchRecordsAfterCommit 后段记录超限 Fail-Fast：对前段外部 ProducerRecord 不产生上下文头注入副作用
        properties.getProducer().setMaxPayloadSize(DataSize.ofBytes(20));
        ProducerRecord<Object, Object> r1 = new ProducerRecord<>("records.tx.topic", "rk1", "ok");
        ProducerRecord<Object, Object> r2 = new ProducerRecord<>("records.tx.topic", "rk2", "0123456789012345678901");
        assertThatThrownBy(() -> template.sendBatchRecordsAfterCommit(List.of(r1, r2)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("超出最大限制");
        assertThat(r1.headers().lastHeader(MessageHeaderConstants.TRACE_ID)).isNull();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        // 4. sendBatchRecordsAfterCommit 正常注册并在存在非空外层上下文时，afterCommit 后精确还原外层上下文
        properties.getProducer().setMaxPayloadSize(DataSize.ofMegabytes(1));
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-records-tx");
        TenantContextHolder.setTenantId("tenant-records-tx");
        UserContextHolder.setUserId("user-records-tx");

        ProducerRecord<Object, Object> validR1 = new ProducerRecord<>("records.tx.topic", "vr1", "v1");
        ProducerRecord<Object, Object> validR2 = new ProducerRecord<>("records.tx.topic", "vr2", "v2");
        template.sendBatchRecordsAfterCommit(List.of(validR1, validR2), Duration.ofSeconds(1));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        // 切换为外层已有不同上下文
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-trace");
        TenantContextHolder.setTenantId("outer-tenant");
        UserContextHolder.setUserId("outer-user");

        AtomicReference<String> inSendTenant = new AtomicReference<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<Object, Object> rec = invocation.getArgument(0);
            inSendTenant.set(TenantContextHolder.getTenantId());
            if ("vr2".equals(rec.key())) {
                // 模拟第 2 条底层同步抛原生异常，验证边界转译为 MessageDeliveryException 并触发 SPI 回调
                throw new IllegalStateException("sdk sync send error");
            }
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(rec.topic(), 0), 1L, 0, System.currentTimeMillis(), 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, metadata));
        });

        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

        assertThat(inSendTenant.get()).isEqualTo("tenant-records-tx");
        // 外层原有上下文必须被准确恢复
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("outer-trace");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-tenant");
        assertThat(UserContextHolder.getUserId()).isEqualTo("outer-user");

        verify(failureCallback, times(1)).onFailure(
                eq("records.tx.topic"),
                isNull(),
                eq("vr2"),
                eq("v2"),
                eq("vr2"),
                eq("trace-records-tx"),
                argThat(ex -> ex instanceof MessageDeliveryException
                        && ex.getCause() instanceof IllegalStateException
                        && ex.getMessage().contains("sdk sync send error"))
        );

        // 5. sendBatchAsync 遇到底层同步 SDK 异常时通过聚合 Future 传播 MessageDeliveryException
        CompletableFuture<List<SendResult<Object, Object>>> asyncFuture =
                template.sendBatchAsync("records.tx.topic", "vr2", List.of("v2"));
        assertThatThrownBy(asyncFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MessageDeliveryException.class);
    }
}
