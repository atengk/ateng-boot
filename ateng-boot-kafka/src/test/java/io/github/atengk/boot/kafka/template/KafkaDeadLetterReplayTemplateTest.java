package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * KafkaDeadLetterReplayTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("KafkaDeadLetterReplayTemplate 死信治理门面测试")
class KafkaDeadLetterReplayTemplateTest {

    private ConsumerFactory<Object, Object> consumerFactory;
    private KafkaTemplate<Object, Object> kafkaTemplate;
    private KafkaAdmin kafkaAdmin;
    private AtengKafkaProperties properties;
    private Consumer<byte[], byte[]> consumer;
    private KafkaDeadLetterReplayTemplate template;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        consumerFactory = mock(ConsumerFactory.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        kafkaAdmin = mock(KafkaAdmin.class);
        properties = new AtengKafkaProperties();
        consumer = mock(Consumer.class);

        when(consumerFactory.createConsumer(anyString(), anyString(), anyString(), any(Properties.class)))
                .thenReturn((Consumer) consumer);

        template = new KafkaDeadLetterReplayTemplate(consumerFactory, kafkaTemplate, kafkaAdmin, properties);

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("边界参数防卫校验：非法入参快速失败 Fail-Fast")
    void testValidationBoundaries() {
        assertThatThrownBy(() -> template.peek(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.peek("", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.peek("dlt", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.peek("dlt", 1001))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.replay(null, "target", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.replay("dlt", null, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.replay("dlt", "target", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.replay("dlt", "target", 1001, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.purge(null))
                .isInstanceOf(IllegalArgumentException.class);

        KafkaDeadLetterReplayTemplate noAdminTemplate = new KafkaDeadLetterReplayTemplate(consumerFactory, kafkaTemplate, null, properties);
        assertThatThrownBy(() -> noAdminTemplate.purge("dlt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KafkaAdmin 未配置或不可用");
    }

    @Test
    @DisplayName("只读探查 peek：拉取死信记录，不提交位移，关闭消费者")
    void testPeekSuccess() {
        String dlqTopic = "order.create.topic.DLT";
        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));

        ConsumerRecord<byte[], byte[]> record1 = new ConsumerRecord<>(dlqTopic, 0, 100L, "k1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<byte[], byte[]> record2 = new ConsumerRecord<>(dlqTopic, 0, 101L, "k2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));

        TopicPartition tp = new TopicPartition(dlqTopic, 0);
        ConsumerRecords<byte[], byte[]> records = new ConsumerRecords<>(Map.of(tp, List.of(record1, record2)));
        ConsumerRecords<byte[], byte[]> emptyRecords = new ConsumerRecords<>(Collections.emptyMap());

        when(consumer.poll(any(Duration.class))).thenReturn(records, emptyRecords);

        List<ConsumerRecord<byte[], byte[]>> peeked = template.peek(dlqTopic, 10);

        assertThat(peeked).hasSize(2);
        assertThat(peeked.get(0).offset()).isEqualTo(100L);
        assertThat(peeked.get(1).offset()).isEqualTo(101L);

        verify(consumer).assign(List.of(tp));
        verify(consumer).seekToBeginning(List.of(tp));
        verify(consumer, never()).commitSync();
        verify(consumer, never()).commitSync(any(Map.class));
        verify(consumer).close();
    }

    @Test
    @DisplayName("受控重放 replay：清洗原错误头，注入重放溯源标头，防二次雪崩熔断，提交位移")
    @SuppressWarnings("unchecked")
    void testReplayWithAntiAvalanche() {
        String dlqTopic = "order.create.topic.DLT";
        String targetTopic = "order.create.topic";

        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));

        // 记录 1：首次重放消息（包含 kafka_dlt-* 头）
        RecordHeaders headers1 = new RecordHeaders();
        headers1.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "boom".getBytes(StandardCharsets.UTF_8)));
        headers1.add(new RecordHeader("Biz-Header", "original-val".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<byte[], byte[]> record1 = new ConsumerRecord<>(dlqTopic, 0, 10L, 0L, null, 0, 0,
                "k1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8), headers1, null);

        // 记录 2：已达最大重放次数 1 的死信消息（应被阻断重放）
        RecordHeaders headers2 = new RecordHeaders();
        headers2.add(new RecordHeader(KafkaDeadLetterHelper.HEADER_X_REPLAY_COUNT, "1".getBytes(StandardCharsets.UTF_8)));
        headers2.add(new RecordHeader(KafkaDeadLetterHelper.HEADER_X_DEAD_LETTER_REPLAYED, "true".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<byte[], byte[]> record2 = new ConsumerRecord<>(dlqTopic, 0, 11L, 0L, null, 0, 0,
                "k2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8), headers2, null);

        TopicPartition tp = new TopicPartition(dlqTopic, 0);
        ConsumerRecords<byte[], byte[]> poll1 = new ConsumerRecords<>(Map.of(tp, List.of(record1, record2)));
        ConsumerRecords<byte[], byte[]> poll2 = new ConsumerRecords<>(Collections.emptyMap());
        when(consumer.poll(any(Duration.class))).thenReturn(poll1, poll2);

        RecordMetadata metadata = new RecordMetadata(new TopicPartition(targetTopic, 0), 20L, 0, 0L, 0, 0);
        SendResult<Object, Object> sendResult = new SendResult<>(null, metadata);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        int replayed = template.replay(dlqTopic, targetTopic, 10);

        // 仅记录 1 成功重放，记录 2 被防二次雪崩熔断拦截
        assertThat(replayed).isEqualTo(1);

        // 校验目标发送的 ProducerRecord
        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(recordCaptor.capture());
        ProducerRecord<Object, Object> sent = recordCaptor.getValue();
        assertThat(sent.topic()).isEqualTo(targetTopic);
        assertThat(sent.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull(); // 已清洗
        assertThat(sent.headers().lastHeader("Biz-Header")).isNotNull(); // 业务头保留
        assertThat(new String(sent.headers().lastHeader(KafkaDeadLetterHelper.HEADER_X_DEAD_LETTER_REPLAYED).value(), StandardCharsets.UTF_8)).isEqualTo("true");
        assertThat(new String(sent.headers().lastHeader(KafkaDeadLetterHelper.HEADER_X_REPLAY_COUNT).value(), StandardCharsets.UTF_8)).isEqualTo("1");
        assertThat(new String(sent.headers().lastHeader(KafkaDeadLetterHelper.HEADER_X_ORIGINAL_DLT_TOPIC).value(), StandardCharsets.UTF_8)).isEqualTo(dlqTopic);

        // 校验位移提交（两条消息的位移均被推进提交，确保已阻断消息不会死循环卡住队列）
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> commitCaptor = ArgumentCaptor.forClass(Map.class);
        verify(consumer, atLeastOnce()).commitSync(commitCaptor.capture());
        OffsetAndMetadata committed = commitCaptor.getValue().get(tp);
        assertThat(committed).isNotNull();
        assertThat(committed.offset()).isEqualTo(12L); // 11 + 1

        verify(consumer).close();
    }

    @Test
    @DisplayName("受控重放 replay：支持谓词过滤器定向重放")
    @SuppressWarnings("unchecked")
    void testReplayWithPredicateFilter() {
        String dlqTopic = "order.create.topic.DLT";
        String targetTopic = "order.create.topic";

        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));

        ConsumerRecord<byte[], byte[]> record1 = new ConsumerRecord<>(dlqTopic, 0, 10L, "VIP-001".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<byte[], byte[]> record2 = new ConsumerRecord<>(dlqTopic, 0, 11L, "NORMAL-002".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));

        TopicPartition tp = new TopicPartition(dlqTopic, 0);
        when(consumer.poll(any(Duration.class))).thenReturn(
                new ConsumerRecords<>(Map.of(tp, List.of(record1, record2))),
                new ConsumerRecords<>(Collections.emptyMap())
        );

        RecordMetadata metadata = new RecordMetadata(new TopicPartition(targetTopic, 0), 20L, 0, 0L, 0, 0);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, metadata)));

        // 仅重放 Key 以 VIP 开头的消息
        int replayed = template.replay(dlqTopic, targetTopic, 10,
                r -> r.key() != null && new String((byte[]) r.key(), StandardCharsets.UTF_8).startsWith("VIP"));

        assertThat(replayed).isEqualTo(1);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }
}
