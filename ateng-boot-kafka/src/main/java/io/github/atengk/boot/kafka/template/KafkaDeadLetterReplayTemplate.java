package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Kafka 死信消息受控治理与安全重放门面
 *
 * <p>负责死信主题消息的安全重放 (replay)、只读探查 (peek) 与主题物理清空 (purge)。
 * 内置单批上限保护、剥离底层原死信错误头（{@code kafka_dlt-*}）、注入 {@code X-Dead-Letter-Replayed} 溯源标记，
 * 并严格防范二次死信雪崩死循环。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class KafkaDeadLetterReplayTemplate {

    public static final String HEADER_X_DEAD_LETTER_REPLAYED = KafkaDeadLetterHelper.HEADER_X_DEAD_LETTER_REPLAYED;
    public static final String HEADER_X_REPLAY_TIME = KafkaDeadLetterHelper.HEADER_X_REPLAY_TIME;
    public static final String HEADER_X_REPLAY_COUNT = KafkaDeadLetterHelper.HEADER_X_REPLAY_COUNT;
    public static final String HEADER_X_ORIGINAL_DLT_TOPIC = KafkaDeadLetterHelper.HEADER_X_ORIGINAL_DLT_TOPIC;

    public static final int DEFAULT_REPLAY_LIMIT = 100;
    public static final int HARD_MAX_REPLAY_LIMIT = 1000;

    private static final String MQ_TYPE_KAFKA = "kafka";

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final AtengKafkaProperties properties;

    @SuppressWarnings("unchecked")
    public KafkaDeadLetterReplayTemplate(
            ConsumerFactory<?, ?> consumerFactory,
            KafkaTemplate<?, ?> kafkaTemplate,
            AtengKafkaProperties properties) {
        this((ConsumerFactory<Object, Object>) consumerFactory, (KafkaTemplate<Object, Object>) kafkaTemplate, null, properties);
    }

    @SuppressWarnings("unchecked")
    public KafkaDeadLetterReplayTemplate(
            ConsumerFactory<?, ?> consumerFactory,
            KafkaTemplate<?, ?> kafkaTemplate,
            KafkaAdmin kafkaAdmin,
            AtengKafkaProperties properties) {
        this.consumerFactory = (ConsumerFactory<Object, Object>) Objects.requireNonNull(consumerFactory, "consumerFactory 不能为空");
        this.kafkaTemplate = (KafkaTemplate<Object, Object>) Objects.requireNonNull(kafkaTemplate, "kafkaTemplate 不能为空");
        this.kafkaAdmin = kafkaAdmin;
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /**
     * 获取底层原生 ConsumerFactory 实例
     */
    public ConsumerFactory<Object, Object> getConsumerFactory() {
        return this.consumerFactory;
    }

    /**
     * 获取底层原生 KafkaTemplate 实例
     */
    public KafkaTemplate<Object, Object> getKafkaTemplate() {
        return this.kafkaTemplate;
    }

    /**
     * 获取底层 KafkaAdmin 实例
     */
    public KafkaAdmin getKafkaAdmin() {
        return this.kafkaAdmin;
    }

    // =========================================================================
    // 1. 只读探查死信 (peek)
    // =========================================================================

    /**
     * 只读探查死信主题头部消息，使用默认单批上限 100
     *
     * @param dlqTopic 死信主题名称
     * @return 探查到的原始消息记录列表（不可修改）
     */
    public List<ConsumerRecord<byte[], byte[]>> peek(String dlqTopic) {
        return peek(dlqTopic, properties.getDeadLetter().getDefaultReplayLimit());
    }

    /**
     * 只读探查死信主题头部指定数量的消息，提取元数据与载荷，不提交位移，完全保持死信主题原貌
     *
     * @param dlqTopic 死信主题名称
     * @param limit    探查最大条数 (1 ~ 1000)
     * @return 探查到的原始消息记录列表（不可修改）
     */
    public List<ConsumerRecord<byte[], byte[]>> peek(String dlqTopic, int limit) {
        Assert.hasText(dlqTopic, "dlqTopic 死信主题名称不能为空");
        Assert.isTrue(limit > 0 && limit <= HARD_MAX_REPLAY_LIMIT,
                String.format("limit 必须在 1 到 %d 之间 (当前: %d)", HARD_MAX_REPLAY_LIMIT, limit));

        String peekGroupId = properties.getDeadLetter().getReplayGroupId() + "-peek-" + IdUtil.fastSimpleUUID();
        Properties props = new Properties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, peekGroupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (Consumer<?, ?> rawConsumer = consumerFactory.createConsumer(
                peekGroupId,
                "peek",
                "-client",
                props)) {
            @SuppressWarnings("unchecked")
            Consumer<byte[], byte[]> consumer = (Consumer<byte[], byte[]>) rawConsumer;

            List<PartitionInfo> partitionInfos = consumer.partitionsFor(dlqTopic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                log.debug("[MQ-DLT-PEEK] 死信主题尚无分区或不存在: dlqTopic={}", dlqTopic);
                return Collections.emptyList();
            }

            List<TopicPartition> topicPartitions = partitionInfos.stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();

            consumer.assign(topicPartitions);
            consumer.seekToBeginning(topicPartitions);

            List<ConsumerRecord<byte[], byte[]>> result = new ArrayList<>(limit);
            long deadline = System.currentTimeMillis() + 5000;

            while (result.size() < limit && System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    result.add(record);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }

            log.info("[MQ-DLT-PEEK] 只读探查死信主题完成: dlqTopic={}, peekedCount={}, limit={}",
                    dlqTopic, result.size(), limit);
            return Collections.unmodifiableList(result);
        }
    }

    // =========================================================================
    // 2. 受控重放死信 (replay)
    // =========================================================================

    /**
     * 从死信主题受控拉取消息，安全清洗并重新入队至目标业务主题 (使用默认单批上限 100)
     *
     * @param dlqTopic    死信主题名称
     * @param targetTopic 目标业务主题名称
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlqTopic, String targetTopic) {
        return replay(dlqTopic, targetTopic, properties.getDeadLetter().getDefaultReplayLimit(), null);
    }

    /**
     * 从死信主题受控拉取消息，使用指定最大条数上限安全重放
     *
     * @param dlqTopic    死信主题名称
     * @param targetTopic 目标业务主题名称
     * @param limit       单批次最大重放数量 (1 ~ 1000)
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlqTopic, String targetTopic, int limit) {
        return replay(dlqTopic, targetTopic, limit, null);
    }

    /**
     * 从死信主题受控拉取消息，使用指定谓词过滤器安全重放
     *
     * @param dlqTopic    死信主题名称
     * @param targetTopic 目标业务主题名称
     * @param filter      消息过滤谓词，为 null 时默认不过滤全部重放
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlqTopic, String targetTopic, Predicate<ConsumerRecord<?, ?>> filter) {
        return replay(dlqTopic, targetTopic, properties.getDeadLetter().getDefaultReplayLimit(), filter);
    }

    /**
     * 从死信主题受控拉取消息，使用指定条数上限与谓词过滤器安全重放
     *
     * @param dlqTopic    死信主题名称
     * @param targetTopic 目标业务主题名称
     * @param limit       单批次最大重放数量 (1 ~ 1000)
     * @param filter      消息过滤谓词，为 null 时默认不过滤全部重放
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlqTopic, String targetTopic, int limit, Predicate<ConsumerRecord<?, ?>> filter) {
        Assert.hasText(dlqTopic, "dlqTopic 死信主题名称不能为空");
        Assert.hasText(targetTopic, "targetTopic 目标业务主题名称不能为空");
        Assert.isTrue(limit > 0 && limit <= HARD_MAX_REPLAY_LIMIT,
                String.format("limit 必须在 1 到 %d 之间 (当前: %d)", HARD_MAX_REPLAY_LIMIT, limit));

        String replayGroupId = properties.getDeadLetter().getReplayGroupId();
        Properties props = new Properties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, replayGroupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        int replayedCount = 0;
        try (Consumer<?, ?> rawConsumer = consumerFactory.createConsumer(
                replayGroupId,
                "replay",
                "-client",
                props)) {
            @SuppressWarnings("unchecked")
            Consumer<byte[], byte[]> consumer = (Consumer<byte[], byte[]>) rawConsumer;

            List<PartitionInfo> partitionInfos = consumer.partitionsFor(dlqTopic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                log.warn("[MQ-DLT-REPLAY] 死信主题不存在或无分区信息: dlqTopic={}", dlqTopic);
                return 0;
            }

            List<TopicPartition> topicPartitions = partitionInfos.stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();

            consumer.assign(topicPartitions);

            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
            long deadline = System.currentTimeMillis() + 10000;

            while (replayedCount < limit && System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    break;
                }

                for (ConsumerRecord<byte[], byte[]> record : records) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    long nextOffset = record.offset() + 1;

                    // 1. 谓词过滤校验
                    if (filter != null && !filter.test(record)) {
                        log.debug("[MQ-DLT-REPLAY-SKIP] 消息被谓词过滤跳过: topic={}, partition={}, offset={}",
                                record.topic(), record.partition(), record.offset());
                        offsetsToCommit.put(tp, new OffsetAndMetadata(nextOffset));
                        continue;
                    }

                    // 2. 防二次死信雪崩熔断检查：防止多次重放失败的消息在业务队列与死信队列之间无限循环震荡
                    int currentReplayCount = KafkaDeadLetterHelper.getReplayCount(record);
                    if (currentReplayCount >= properties.getDeadLetter().getMaxReplayCount()) {
                        log.error("[MQ-DLT-REPLAY-BLOCKED] 死信消息已达最大重放次数上限 ({} 次)，已阻断重放，防范二次死信雪崩死循环: dlqTopic={}, targetTopic={}, partition={}, offset={}",
                                currentReplayCount, dlqTopic, targetTopic, record.partition(), record.offset());
                        offsetsToCommit.put(tp, new OffsetAndMetadata(nextOffset));
                        continue;
                    }

                    // 3. 安全清洗原始错误头并注入重放溯源元数据
                    ProducerRecord<Object, Object> preparedRecord = prepareReplayRecord(record, targetTopic, currentReplayCount + 1, dlqTopic);

                    // 4. 发送到目标业务主题
                    try {
                        kafkaTemplate.send(preparedRecord).get(properties.getProducer().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    } catch (Exception ex) {
                        log.error("[MQ-DLT-REPLAY-FAILED] 重发消息至目标主题失败: targetTopic={}, partition={}, offset={}, error={}",
                                targetTopic, record.partition(), record.offset(), ex.getMessage(), ex);
                        String bizKey = record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null;
                        throw new MessageDeliveryException(MQ_TYPE_KAFKA, targetTopic, bizKey, "死信重放发送至目标主题失败: " + ex.getMessage(), ex);
                    }

                    offsetsToCommit.put(tp, new OffsetAndMetadata(nextOffset));
                    replayedCount++;

                    if (replayedCount >= limit) {
                        break;
                    }
                }

                if (!offsetsToCommit.isEmpty()) {
                    consumer.commitSync(new HashMap<>(offsetsToCommit));
                    offsetsToCommit.clear();
                }
            }

            if (!offsetsToCommit.isEmpty()) {
                consumer.commitSync(new HashMap<>(offsetsToCommit));
            }

            log.info("[MQ-DLT-REPLAY] 死信队列重放执行完成: dlqTopic={}, targetTopic={}, replayedCount={}, limit={}",
                    dlqTopic, targetTopic, replayedCount, limit);
            return replayedCount;
        }
    }

    // =========================================================================
    // 3. 物理清空死信 (purge)
    // =========================================================================

    /**
     * 物理清空死信主题所有历史消息（基于 Kafka AdminClient deleteRecords 推进起始位移至最新 End Offset）
     *
     * @param dlqTopic 死信主题名称
     */
    public void purge(String dlqTopic) {
        Assert.hasText(dlqTopic, "dlqTopic 死信主题名称不能为空");
        if (this.kafkaAdmin == null) {
            throw new IllegalStateException("KafkaAdmin 未配置或不可用，无法执行物理 purge 操作: dlqTopic=" + dlqTopic);
        }

        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            // 1. 获取主题全部分区
            DescribeTopicsResult describeTopicsResult = adminClient.describeTopics(Collections.singletonList(dlqTopic));
            TopicDescription description = describeTopicsResult.values().get(dlqTopic).get(10, TimeUnit.SECONDS);
            List<TopicPartition> partitions = description.partitions().stream()
                    .map(p -> new TopicPartition(dlqTopic, p.partition()))
                    .toList();

            if (partitions.isEmpty()) {
                return;
            }

            // 2. 查询各个分区的最新高水位位移 (Log End Offset)
            Map<TopicPartition, OffsetSpec> offsetSpecMap = new HashMap<>();
            for (TopicPartition tp : partitions) {
                offsetSpecMap.put(tp, OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                    adminClient.listOffsets(offsetSpecMap).all().get(10, TimeUnit.SECONDS);

            // 3. 构造 deleteRecords 映射 (删除 endOffset 之前的所有历史消息)
            Map<TopicPartition, RecordsToDelete> recordsToDeleteMap = new HashMap<>();
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> entry : endOffsets.entrySet()) {
                long endOffset = entry.getValue().offset();
                if (endOffset > 0) {
                    recordsToDeleteMap.put(entry.getKey(), RecordsToDelete.beforeOffset(endOffset));
                }
            }

            if (!recordsToDeleteMap.isEmpty()) {
                adminClient.deleteRecords(recordsToDeleteMap).all().get(10, TimeUnit.SECONDS);
                log.info("[MQ-DLT-PURGE] 成功物理清空死信主题历史消息: dlqTopic={}, partitions={}", dlqTopic, recordsToDeleteMap.keySet());
            } else {
                log.info("[MQ-DLT-PURGE] 死信主题各分区最新位移均为 0，无需清理: dlqTopic={}", dlqTopic);
            }
        } catch (Exception ex) {
            log.error("[MQ-DLT-PURGE-FAILED] 清空死信主题失败: dlqTopic={}, error={}", dlqTopic, ex.getMessage(), ex);
            throw new MessageDeliveryException(MQ_TYPE_KAFKA, dlqTopic, null, "物理清空死信主题失败: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 内部消息重构辅助方法
    // =========================================================================

    private ProducerRecord<Object, Object> prepareReplayRecord(
            ConsumerRecord<byte[], byte[]> record,
            String targetTopic,
            int nextReplayCount,
            String originalDlqTopic) {

        RecordHeaders newHeaders = new RecordHeaders();
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                if (header != null && header.key() != null) {
                    String key = header.key();
                    // 清洗底层错误标头与旧重放标记
                    if (key.startsWith("kafka_dlt-")
                            || key.equals(HEADER_X_DEAD_LETTER_REPLAYED)
                            || key.equals(HEADER_X_REPLAY_TIME)
                            || key.equals(HEADER_X_REPLAY_COUNT)
                            || key.equals(HEADER_X_ORIGINAL_DLT_TOPIC)) {
                        continue;
                    }
                    newHeaders.add(header);
                }
            }
        }

        // 注入重放溯源元数据
        newHeaders.add(new RecordHeader(HEADER_X_DEAD_LETTER_REPLAYED, "true".getBytes(StandardCharsets.UTF_8)));
        newHeaders.add(new RecordHeader(HEADER_X_REPLAY_TIME, String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)));
        newHeaders.add(new RecordHeader(HEADER_X_REPLAY_COUNT, String.valueOf(nextReplayCount).getBytes(StandardCharsets.UTF_8)));
        newHeaders.add(new RecordHeader(HEADER_X_ORIGINAL_DLT_TOPIC, originalDlqTopic.getBytes(StandardCharsets.UTF_8)));

        Long replayTimestamp = record.timestamp() >= 0 ? record.timestamp() : null;
        return new ProducerRecord<>(
                targetTopic,
                null,
                replayTimestamp,
                record.key(),
                record.value(),
                newHeaders
        );
    }
}
