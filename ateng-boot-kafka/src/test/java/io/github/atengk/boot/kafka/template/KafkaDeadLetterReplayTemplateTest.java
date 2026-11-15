package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DeleteRecordsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        assertThatThrownBy(() -> template.peekDiagnosed(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.peekDiagnosed("  ", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.peekDiagnosed("dlt", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.peekDiagnosed("dlt", 1001))
                .isInstanceOf(IllegalArgumentException.class);

        KafkaDeadLetterReplayTemplate noAdminTemplate = new KafkaDeadLetterReplayTemplate(consumerFactory, kafkaTemplate, null, properties);
        assertThatThrownBy(() -> noAdminTemplate.purge("dlt"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("KafkaAdmin 未配置或不可用")
                .hasCauseInstanceOf(IllegalStateException.class);
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

    @Test
    @DisplayName("物理清空 purge：支持通过 Supplier<AdminClient> 对称接缝推进位移至 End Offset 并确定性关闭客户端")
    @SuppressWarnings("unchecked")
    void testPurgeThroughAdminClientSupplierSeam() {
        String dlqTopic = "order.create.topic.DLT";
        TopicPartition tp0 = new TopicPartition(dlqTopic, 0);

        AdminClient adminClient = mock(AdminClient.class);
        TopicDescription description = new TopicDescription(
                dlqTopic, false,
                List.of(new TopicPartitionInfo(0, Node.noNode(), List.of(), List.of()))
        );
        DescribeTopicsResult describeResult = mock(DescribeTopicsResult.class);
        when(describeResult.values()).thenReturn(Map.of(dlqTopic, KafkaFuture.completedFuture(description)));
        when(adminClient.describeTopics(Collections.singletonList(dlqTopic))).thenReturn(describeResult);

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(
                Map.of(tp0, new ListOffsetsResult.ListOffsetsResultInfo(15L, 0L, Optional.empty()))
        ));
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        DeleteRecordsResult deleteResult = mock(DeleteRecordsResult.class);
        KafkaFutureImpl<Void> deleteFuture = new KafkaFutureImpl<>();
        deleteFuture.complete(null);
        when(deleteResult.all()).thenReturn(deleteFuture);
        when(adminClient.deleteRecords(anyMap())).thenReturn(deleteResult);

        KafkaDeadLetterReplayTemplate customTemplate = new KafkaDeadLetterReplayTemplate(
                consumerFactory, kafkaTemplate, properties, () -> adminClient
        );
        customTemplate.purge(dlqTopic);

        ArgumentCaptor<Map<TopicPartition, RecordsToDelete>> deleteCaptor = ArgumentCaptor.forClass(Map.class);
        verify(adminClient).deleteRecords(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().get(tp0).beforeOffset()).isEqualTo(15L);
        verify(adminClient).close();
    }

    @Test
    @DisplayName("结构化诊断探查 peekDiagnosed：解析 DLT 头与消息键值，不提交位移且返回不可变列表")
    @SuppressWarnings("unchecked")
    void testPeekDiagnosedSuccess() {
        String dlqTopic = "order.create.topic.DLT";
        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));

        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, "order.create.topic".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "毒丸参数非法".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                dlqTopic, 0, 100L, 1700000000000L, null, 0, 0,
                "order-key-1".getBytes(StandardCharsets.UTF_8),
                "{\"orderId\":\"ORD-984\"}".getBytes(StandardCharsets.UTF_8),
                headers,
                Optional.empty()
        );

        TopicPartition tp = new TopicPartition(dlqTopic, 0);
        when(consumer.poll(any(Duration.class))).thenReturn(
                new ConsumerRecords<>(Map.of(tp, List.of(record))),
                new ConsumerRecords<>(Collections.emptyMap())
        );

        properties.getDeadLetter().setDefaultReplayLimit(5);
        List<KafkaDeadLetterHelper.DeadLetterRecord> diagnosed = template.peekDiagnosed(dlqTopic);

        assertThat(diagnosed).hasSize(1);
        KafkaDeadLetterHelper.DeadLetterRecord item = diagnosed.get(0);
        assertThat(item.originalTopic()).isEqualTo("order.create.topic");
        assertThat(item.exceptionClassName()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(item.exceptionMessage()).isEqualTo("毒丸参数非法");
        assertThat(item.key()).isEqualTo("order-key-1");
        assertThat(item.payload()).isEqualTo("{\"orderId\":\"ORD-984\"}");
        assertThatThrownBy(() -> diagnosed.add(item)).isInstanceOf(UnsupportedOperationException.class);

        verify(consumer, never()).commitSync();
        verify(consumer, never()).commitSync(any(Map.class));
        verify(consumer).close();
    }

    @Test
    @DisplayName("可配置超时生效验证：peekTimeout 与 operationTimeout 驱动轮询与 AdminClient Future 等待")
    @SuppressWarnings("unchecked")
    void testConfigurableTimeoutsApplied() throws Exception {
        properties.getDeadLetter().setPeekTimeout(Duration.ofMillis(120));
        properties.getDeadLetter().setOperationTimeout(Duration.ofMillis(150));

        String dlqTopic = "timeout.topic.DLT";
        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));
        when(consumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Collections.emptyMap()));

        template.peek(dlqTopic, 5);
        ArgumentCaptor<Duration> peekPollCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(consumer).poll(peekPollCaptor.capture());
        assertThat(peekPollCaptor.getValue()).isLessThanOrEqualTo(Duration.ofMillis(120));

        reset(consumer);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));
        when(consumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Collections.emptyMap()));
        template.replay(dlqTopic, "timeout.topic", 5);
        ArgumentCaptor<Duration> replayPollCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(consumer).poll(replayPollCaptor.capture());
        assertThat(replayPollCaptor.getValue()).isLessThanOrEqualTo(Duration.ofMillis(150));

        AdminClient adminClient = mock(AdminClient.class);
        TopicPartition tp0 = new TopicPartition(dlqTopic, 0);
        TopicDescription description = new TopicDescription(
                dlqTopic, false, List.of(new TopicPartitionInfo(0, Node.noNode(), List.of(), List.of()))
        );
        KafkaFuture<TopicDescription> describeFuture = mock(KafkaFuture.class);
        when(describeFuture.get(150L, TimeUnit.MILLISECONDS)).thenReturn(description);
        DescribeTopicsResult describeResult = mock(DescribeTopicsResult.class);
        when(describeResult.values()).thenReturn(Map.of(dlqTopic, describeFuture));
        when(adminClient.describeTopics(Collections.singletonList(dlqTopic))).thenReturn(describeResult);

        KafkaFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> listFuture = mock(KafkaFuture.class);
        when(listFuture.get(150L, TimeUnit.MILLISECONDS)).thenReturn(
                Map.of(tp0, new ListOffsetsResult.ListOffsetsResultInfo(8L, 0L, Optional.empty()))
        );
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(listOffsetsResult.all()).thenReturn(listFuture);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        KafkaFuture<Void> deleteFuture = mock(KafkaFuture.class);
        when(deleteFuture.get(150L, TimeUnit.MILLISECONDS)).thenReturn(null);
        DeleteRecordsResult deleteResult = mock(DeleteRecordsResult.class);
        when(deleteResult.all()).thenReturn(deleteFuture);
        when(adminClient.deleteRecords(anyMap())).thenReturn(deleteResult);

        KafkaDeadLetterReplayTemplate customTemplate = new KafkaDeadLetterReplayTemplate(
                consumerFactory, kafkaTemplate, properties, () -> adminClient
        );
        customTemplate.purge(dlqTopic);

        verify(describeFuture).get(150L, TimeUnit.MILLISECONDS);
        verify(listFuture).get(150L, TimeUnit.MILLISECONDS);
        verify(deleteFuture).get(150L, TimeUnit.MILLISECONDS);
        verify(adminClient).close();
    }

    @Test
    @DisplayName("purge 异常转译闭环：AdminClient 运行期失败与空提供者统一抛出保留 cause 的 MessageDeliveryException")
    void testPurgeTranslatesRuntimeFailuresPreservingCause() {
        String dlqTopic = "broken.topic.DLT";

        KafkaDeadLetterReplayTemplate nullSupplierTemplate = new KafkaDeadLetterReplayTemplate(
                consumerFactory, kafkaTemplate, properties, () -> null
        );
        assertThatThrownBy(() -> nullSupplierTemplate.purge(dlqTopic))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        AdminClient adminClient = mock(AdminClient.class);
        KafkaFutureImpl<TopicDescription> failedFuture = new KafkaFutureImpl<>();
        IllegalStateException rootBrokerError = new IllegalStateException("模拟元数据查询异常");
        failedFuture.completeExceptionally(rootBrokerError);
        DescribeTopicsResult describeResult = mock(DescribeTopicsResult.class);
        when(describeResult.values()).thenReturn(Map.of(dlqTopic, failedFuture));
        when(adminClient.describeTopics(Collections.singletonList(dlqTopic))).thenReturn(describeResult);

        KafkaDeadLetterReplayTemplate brokenTemplate = new KafkaDeadLetterReplayTemplate(
                consumerFactory, kafkaTemplate, properties, () -> adminClient
        );
        assertThatThrownBy(() -> brokenTemplate.purge(dlqTopic))
                .isInstanceOf(MessageDeliveryException.class)
                .hasRootCause(rootBrokerError);
        verify(adminClient).close();
    }

    @Test
    @DisplayName("非 ByteArraySerializer 工厂重放：自动派生 ByteArraySerializer 通道避免 String/Json 序列化崩溃并支持生命周期销毁")
    @SuppressWarnings("unchecked")
    void testReplayDerivesByteArraySerializerFactoryWhenNonByteArrayConfigured() throws Exception {
        String dlqTopic = "order.create.topic.DLT";
        String targetTopic = "order.create.topic";

        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));

        byte[] rawKey = "dlt-key-1".getBytes(StandardCharsets.UTF_8);
        byte[] rawValue = "{\"orderId\":\"ORD-1\"}".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<byte[], byte[]> dltRecord = new ConsumerRecord<>(dlqTopic, 0, 5L, rawKey, rawValue);
        TopicPartition tp = new TopicPartition(dlqTopic, 0);
        when(consumer.poll(any(Duration.class))).thenReturn(
                new ConsumerRecords<>(Map.of(tp, List.of(dltRecord))),
                new ConsumerRecords<>(Collections.emptyMap())
        );

        Producer<Object, Object> rawProducer = mock(Producer.class);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(targetTopic, 0), 1L, 0, 0L, 0, 0);
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class))).thenAnswer(inv -> {
            Callback cb = inv.getArgument(1);
            if (cb != null) {
                cb.onCompletion(metadata, null);
            }
            return CompletableFuture.completedFuture(metadata);
        });

        Map<String, Object> baseConfigs = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        );
        final DefaultKafkaProducerFactory<Object, Object>[] capturedCopy = new DefaultKafkaProducerFactory[1];
        DefaultKafkaProducerFactory<Object, Object> baseFactory = new DefaultKafkaProducerFactory<>(baseConfigs) {
            @Override
            public ProducerFactory<Object, Object> copyWithConfigurationOverride(Map<String, Object> overrideConfigs) {
                Map<String, Object> merged = new java.util.HashMap<>(getConfigurationProperties());
                merged.putAll(overrideConfigs);
                DefaultKafkaProducerFactory<Object, Object> copy = spy(new DefaultKafkaProducerFactory<>(merged) {
                    @Override
                    protected Producer<Object, Object> createRawProducer(Map<String, Object> rawConfigs) {
                        return rawProducer;
                    }
                });
                capturedCopy[0] = copy;
                return copy;
            }
        };

        KafkaTemplate<Object, Object> realTemplate = spy(new KafkaTemplate<>(baseFactory));
        try (KafkaDeadLetterReplayTemplate replayTemplate =
                     new KafkaDeadLetterReplayTemplate(consumerFactory, realTemplate, kafkaAdmin, properties)) {
            int replayed = replayTemplate.replay(dlqTopic, targetTopic, 10);
            assertThat(replayed).isEqualTo(1);

            verify(realTemplate, never()).send(any(ProducerRecord.class));
            assertThat(capturedCopy[0]).isNotNull();
            assertThat(capturedCopy[0].getConfigurationProperties())
                    .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                    .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            assertThat(capturedCopy[0].getKeySerializerSupplier().get()).isInstanceOf(ByteArraySerializer.class);
            assertThat(capturedCopy[0].getValueSerializerSupplier().get()).isInstanceOf(ByteArraySerializer.class);

            ArgumentCaptor<ProducerRecord<Object, Object>> sentCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(rawProducer).send(sentCaptor.capture(), any(Callback.class));
            assertThat(sentCaptor.getValue().topic()).isEqualTo(targetTopic);
            assertThat(sentCaptor.getValue().key()).isEqualTo(rawKey);
            assertThat(sentCaptor.getValue().value()).isEqualTo(rawValue);
        }

        verify(capturedCopy[0]).destroy();
    }

    @Test
    @DisplayName("已配置 ByteArraySerializer 工厂重放：直接复用主 KafkaTemplate 不重复派生工厂")
    @SuppressWarnings("unchecked")
    void testReplayReusesMainTemplateWhenByteArraySerializerAlreadyConfigured() {
        String dlqTopic = "order.create.topic.DLT";
        String targetTopic = "order.create.topic";

        PartitionInfo pi = new PartitionInfo(dlqTopic, 0, null, null, null);
        when(consumer.partitionsFor(dlqTopic)).thenReturn(List.of(pi));

        ConsumerRecord<byte[], byte[]> dltRecord = new ConsumerRecord<>(
                dlqTopic, 0, 5L,
                "k".getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8)
        );
        TopicPartition tp = new TopicPartition(dlqTopic, 0);
        when(consumer.poll(any(Duration.class))).thenReturn(
                new ConsumerRecords<>(Map.of(tp, List.of(dltRecord))),
                new ConsumerRecords<>(Collections.emptyMap())
        );

        Producer<Object, Object> rawProducer = mock(Producer.class);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(targetTopic, 0), 1L, 0, 0L, 0, 0);
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class))).thenAnswer(inv -> {
            Callback cb = inv.getArgument(1);
            if (cb != null) {
                cb.onCompletion(metadata, null);
            }
            return CompletableFuture.completedFuture(metadata);
        });

        Map<String, Object> byteArrayConfigs = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class
        );
        DefaultKafkaProducerFactory<Object, Object> byteArrayFactory = spy(new DefaultKafkaProducerFactory<>(byteArrayConfigs) {
            @Override
            protected Producer<Object, Object> createRawProducer(Map<String, Object> rawConfigs) {
                return rawProducer;
            }
        });

        KafkaTemplate<Object, Object> directTemplate = spy(new KafkaTemplate<>(byteArrayFactory));
        KafkaDeadLetterReplayTemplate directReplayTemplate =
                new KafkaDeadLetterReplayTemplate(consumerFactory, directTemplate, kafkaAdmin, properties);
        int replayed = directReplayTemplate.replay(dlqTopic, targetTopic, 10);

        assertThat(replayed).isEqualTo(1);
        verify(directTemplate, times(1)).send(any(ProducerRecord.class));
        verify(byteArrayFactory, never()).copyWithConfigurationOverride(anyMap());
    }
}
