package io.github.atengk.boot.kafka.helper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KafkaDeadLetterHelper 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("KafkaDeadLetterHelper 死信诊断工具测试")
class KafkaDeadLetterHelperTest {

    @Test
    @DisplayName("空安全校验：传入 null 时返回 null 或安全默认值")
    void testNullSafety() {
        assertThat(KafkaDeadLetterHelper.extractDeadLetterRecord((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.extractDeadLetterRecord((Headers) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getOriginalTopic((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getOriginalPartition((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getOriginalOffset((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getOriginalTimestamp((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getExceptionClassName((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getExceptionMessage((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getExceptionStackTrace((ConsumerRecord<?, ?>) null)).isNull();
        assertThat(KafkaDeadLetterHelper.getReplayCount((ConsumerRecord<?, ?>) null)).isEqualTo(0);
        assertThat(KafkaDeadLetterHelper.isReplayed((ConsumerRecord<?, ?>) null)).isFalse();
    }

    @Test
    @DisplayName("标准 ByteBuffer 二进制头解析验证")
    void testParseBinaryHeaders() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, "order.create.topic".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(2).array()));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(10086L).array()));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, ByteBuffer.allocate(Long.BYTES).putLong(1698500000000L).array()));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "参数校验失败".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, "at com.example.Service.process(Service.java:42)".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaDeadLetterHelper.HEADER_X_REPLAY_COUNT, ByteBuffer.allocate(Integer.BYTES).putInt(1).array()));
        headers.add(new RecordHeader(KafkaDeadLetterHelper.HEADER_X_DEAD_LETTER_REPLAYED, "true".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.create.topic.DLT", 0, 10L, "k1", "v1");
        headers.forEach(h -> record.headers().add(h));

        KafkaDeadLetterHelper.DeadLetterRecord result = KafkaDeadLetterHelper.extractDeadLetterRecord(record);
        assertThat(result).isNotNull();
        assertThat(result.originalTopic()).isEqualTo("order.create.topic");
        assertThat(result.originalPartition()).isEqualTo(2);
        assertThat(result.originalOffset()).isEqualTo(10086L);
        assertThat(result.originalTimestamp()).isEqualTo(1698500000000L);
        assertThat(result.exceptionClassName()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(result.exceptionMessage()).isEqualTo("参数校验失败");
        assertThat(result.exceptionStackTrace()).contains("Service.java:42");
        assertThat(result.replayCount()).isEqualTo(1);
        assertThat(result.isReplayed()).isTrue();
    }

    @Test
    @DisplayName("String 字符串字节头兼容解析验证")
    void testParseStringEncodedHeaders() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, "pay.notify.topic".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION, "5".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET, "99999".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, "1700000000000".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN, "io.github.atengk.boot.core.exception.BusinessException".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "业务执行超时".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaDeadLetterHelper.HEADER_X_REPLAY_COUNT, "2".getBytes(StandardCharsets.UTF_8)));

        KafkaDeadLetterHelper.DeadLetterRecord result = KafkaDeadLetterHelper.extractDeadLetterRecord(headers);
        assertThat(result).isNotNull();
        assertThat(result.originalTopic()).isEqualTo("pay.notify.topic");
        assertThat(result.originalPartition()).isEqualTo(5);
        assertThat(result.originalOffset()).isEqualTo(99999L);
        assertThat(result.originalTimestamp()).isEqualTo(1700000000000L);
        assertThat(result.exceptionClassName()).isEqualTo("io.github.atengk.boot.core.exception.BusinessException");
        assertThat(result.exceptionMessage()).isEqualTo("业务执行超时");
        assertThat(result.replayCount()).isEqualTo(2);
        assertThat(result.isReplayed()).isFalse();
    }

    @Test
    @DisplayName("畸形或空字节数组防卫：不抛异常，返回 null 或默认值")
    void testMalformedHeadersSafety() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION, "NOT_AN_INT".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET, new byte[]{1, 2})); // 2 字节既非 8 字节又非合法长整型文本
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, new byte[0]));

        assertThat(KafkaDeadLetterHelper.getOriginalPartition(headers)).isNull();
        assertThat(KafkaDeadLetterHelper.getOriginalOffset(headers)).isNull();
        assertThat(KafkaDeadLetterHelper.getOriginalTopic(headers)).isNull();
        assertThat(KafkaDeadLetterHelper.getReplayCount(headers)).isEqualTo(0);
        assertThat(KafkaDeadLetterHelper.isReplayed(headers)).isFalse();
    }
}
