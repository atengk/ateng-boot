package io.github.atengk.boot.kafka.interceptor;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 批量消费拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("KafkaBatchInterceptor 批量消费拦截器单元测试")
class KafkaBatchInterceptorTest {

    private final KafkaBatchInterceptor interceptor = new KafkaBatchInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("批量拦截成功生成批次 TraceId 并在成功后清理 MDC")
    void testBatchSuccess() {
        TopicPartition tp = new TopicPartition("batch.topic", 0);
        ConsumerRecord<Object, Object> record1 = new ConsumerRecord<>("batch.topic", 0, 1L, "k1", "v1");
        ConsumerRecord<Object, Object> record2 = new ConsumerRecord<>("batch.topic", 0, 2L, "k2", "v2");
        ConsumerRecords<Object, Object> records = new ConsumerRecords<>(Map.of(tp, List.of(record1, record2)));

        ConsumerRecords<Object, Object> intercepted = interceptor.intercept(records, null);
        assertThat(intercepted).isSameAs(records);

        String batchTraceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        assertThat(batchTraceId).isNotNull().startsWith("batch-");

        interceptor.success(records, null);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("批量消费失败时正常记录异常并在 finally 释放 MDC")
    void testBatchFailure() {
        TopicPartition tp = new TopicPartition("batch.topic", 0);
        ConsumerRecord<Object, Object> record1 = new ConsumerRecord<>("batch.topic", 0, 1L, "k1", "v1");
        ConsumerRecords<Object, Object> records = new ConsumerRecords<>(Map.of(tp, List.of(record1)));

        interceptor.intercept(records, null);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNotNull().startsWith("batch-");

        interceptor.failure(records, new RuntimeException("Batch error"), null);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("空 records 拦截安全防卫")
    void testEmptyRecords() {
        ConsumerRecords<Object, Object> empty = new ConsumerRecords<>(Collections.emptyMap());
        ConsumerRecords<Object, Object> result = interceptor.intercept(empty, null);
        assertThat(result).isSameAs(empty);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();

        ConsumerRecords<Object, Object> nullResult = interceptor.intercept(null, null);
        assertThat(nullResult).isNull();
    }
}
