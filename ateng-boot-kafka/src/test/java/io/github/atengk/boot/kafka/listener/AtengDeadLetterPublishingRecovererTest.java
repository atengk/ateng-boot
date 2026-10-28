package io.github.atengk.boot.kafka.listener;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 死信发布恢复器与终点不变量测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengDeadLetterPublishingRecoverer 死信恢复器与终点治理测试")
class AtengDeadLetterPublishingRecovererTest {

    private final AtengKafkaProperties properties = new AtengKafkaProperties();

    @Test
    @DisplayName("普通业务异常转发至 ${topic}.DLT 并指定分区为 -1")
    void testResolveDestinationNormalFailure() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.topic", 2, 100L, "order-001", "body");
        record.headers().add(new RecordHeader(MessageHeaderConstants.TRACE_ID, "trace-101".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(MessageHeaderConstants.BIZ_KEY, "biz-101".getBytes(StandardCharsets.UTF_8)));

        Exception ex = new RuntimeException("Business process failed");

        TopicPartition destination = AtengDeadLetterPublishingRecoverer.resolveDestination(record, ex, properties);

        assertThat(destination).isNotNull();
        assertThat(destination.topic()).isEqualTo("order.topic.DLT");
        assertThat(destination.partition()).isEqualTo(-1);
    }

    @Test
    @DisplayName("守卫 DLT 终点不变量：已在死信队列的消息失败不再二次转发，返回 null")
    void testTerminalInvariantOnDltTopic() {
        ConsumerRecord<String, String> dltRecord = new ConsumerRecord<>("order.topic.DLT", 0, 50L, "order-001", "bad-body");
        Exception ex = new RuntimeException("DLT consumer retry failed");

        TopicPartition destination = AtengDeadLetterPublishingRecoverer.resolveDestination(dltRecord, ex, properties);

        // 终点隔离，禁止互相弹射产生死循环
        assertThat(destination).isNull();
    }

    @Test
    @DisplayName("捕获 IdempotentException 幂等异常静默提交：返回 null 且不发 DLT")
    void testIdempotentExceptionSilentCommit() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.topic", 1, 20L, "pay-888", "pay-data");
        IdempotentException idempEx = new IdempotentException("Duplicate payment request");

        TopicPartition destination = AtengDeadLetterPublishingRecoverer.resolveDestination(record, idempEx, properties);

        // 幂等重复属于预期业务状态，静默确认 Offset，严禁进入死信
        assertThat(destination).isNull();
    }

    @Test
    @DisplayName("支持自定义 dltSuffix 后缀配置")
    void testCustomDltSuffix() {
        AtengKafkaProperties customProps = new AtengKafkaProperties();
        customProps.getConsumer().setDltSuffix(".DEAD");

        ConsumerRecord<String, String> record = new ConsumerRecord<>("user.topic", 0, 1L, "k", "v");
        TopicPartition destination = AtengDeadLetterPublishingRecoverer.resolveDestination(record, new RuntimeException("err"), customProps);

        assertThat(destination).isNotNull();
        assertThat(destination.topic()).isEqualTo("user.topic.DEAD");

        // 终点不变量针对自定义后缀同样生效
        ConsumerRecord<String, String> deadRecord = new ConsumerRecord<>("user.topic.DEAD", 0, 2L, "k", "v");
        TopicPartition deadDest = AtengDeadLetterPublishingRecoverer.resolveDestination(deadRecord, new RuntimeException("err"), customProps);
        assertThat(deadDest).isNull();
    }
}
