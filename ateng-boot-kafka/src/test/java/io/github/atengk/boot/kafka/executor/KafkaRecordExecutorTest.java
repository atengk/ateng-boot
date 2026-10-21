package io.github.atengk.boot.kafka.executor;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 批量执行器多租户安全隔离与容错测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("KafkaRecordExecutor 批量多租户隔离与容错执行器测试")
class KafkaRecordExecutorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("generateBatchTraceId 生成带 batch- 前缀的全局 TraceId")
    void testGenerateBatchTraceId() {
        String batchTraceId = KafkaRecordExecutor.generateBatchTraceId();
        assertThat(batchTraceId).isNotNull().startsWith("batch-");
    }

    @Test
    @DisplayName("runInContext 自动还原四维上下文并在 finally 中确定性排空")
    void testRunInContext() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 1L, "k", "payload");
        record.headers().add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-99".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(MessageHeaderConstants.USER_ID, "user-55".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(MessageHeaderConstants.TRACE_ID, "trace-xyz".getBytes(StandardCharsets.UTF_8)));

        AtomicBoolean executed = new AtomicBoolean(false);

        KafkaRecordExecutor.runInContext(record, r -> {
            executed.set(true);
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-99");
            assertThat(UserContextHolder.get()).isEqualTo("user-55");
            assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("trace-xyz");
        });

        assertThat(executed.get()).isTrue();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("callInContext 闭包安全执行并获取返回值")
    void testCallInContext() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 1L, "k", "hello");
        record.headers().add(new RecordHeader(MessageHeaderConstants.TRACE_ID, "t-100".getBytes(StandardCharsets.UTF_8)));

        String result = KafkaRecordExecutor.callInContext(record, (rec, val) -> {
            assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("t-100");
            return val.toUpperCase();
        });

        assertThat(result).isEqualTo("HELLO");
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("processBatchSafely 保证批量记录多租户零串门，每条独立隔离")
    void testMultiTenantZeroLeakageInBatch() {
        // 构造三条不同租户的记录
        ConsumerRecord<String, String> r1 = new ConsumerRecord<>("topic", 0, 1L, "k1", "data-1");
        r1.headers().add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-A".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> r2 = new ConsumerRecord<>("topic", 0, 2L, "k2", "data-2");
        r2.headers().add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-B".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> r3 = new ConsumerRecord<>("topic", 0, 3L, "k3", "data-3");
        // r3 无租户 Header

        List<ConsumerRecord<String, String>> batch = List.of(r1, r2, r3);
        List<String> capturedTenants = new ArrayList<>();

        KafkaRecordExecutor.processBatchSafely(batch, (rec, val) -> {
            capturedTenants.add(TenantContextHolder.getTenantId());
        });

        assertThat(capturedTenants).containsExactly("tenant-A", "tenant-B", null);
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("processBatchSafely 单条失败回调 failureHandler，不阻断剩余正常记录处理")
    void testBatchFaultTolerance() {
        ConsumerRecord<String, String> r1 = new ConsumerRecord<>("topic", 0, 1L, "k1", "ok-1");
        ConsumerRecord<String, String> r2 = new ConsumerRecord<>("topic", 0, 2L, "k2", "bad-2");
        ConsumerRecord<String, String> r3 = new ConsumerRecord<>("topic", 0, 3L, "k3", "ok-3");

        List<ConsumerRecord<String, String>> batch = List.of(r1, r2, r3);

        List<String> successList = new ArrayList<>();
        List<String> failedKeys = new ArrayList<>();

        KafkaRecordExecutor.processBatchSafely(batch, (rec, val) -> {
            if ("bad-2".equals(val)) {
                throw new IllegalStateException("Corrupted data in record 2");
            }
            successList.add(val);
        }, (failedRecord, ex) -> {
            failedKeys.add(failedRecord.key());
            assertThat(ex).isInstanceOf(IllegalStateException.class).hasMessage("Corrupted data in record 2");
        });

        // 验证其余正常记录被完全处理，单条失败未卡死批处理
        assertThat(successList).containsExactly("ok-1", "ok-3");
        assertThat(failedKeys).containsExactly("k2");
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("processBatchSafely 空入参防卫测试")
    void testNullSafely() {
        // 不应抛出任何异常
        KafkaRecordExecutor.processBatchSafely(null, (rec, val) -> {});
        KafkaRecordExecutor.processBatchSafely(List.of(), (rec, val) -> {});
        KafkaRecordExecutor.runInContext(null, rec -> {});
    }
}
