package io.github.atengk.boot.kafka.manager;

import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Kafka 消费组位移治理与实时 Lag 积压探测运维门面
 *
 * <p>封装底层 {@link AdminClient} 进行高可用位移回溯与积压探测，
 * 所有物理连接均基于 {@code try-with-resources} 确定性销毁闭环并注入严格超时熔断机制，
 * 杜绝连接泄露与无界线程挂死。提供以下核心运维治理能力：
 * <ul>
 *   <li>位移安全查询：查询消费组全主题或特定主题的分区已提交位移与元数据快照；</li>
 *   <li>位移精准回溯：支持按时间戳（{@link Instant} / 毫秒）回退消费位移（用于故障自愈、重放对账与数据修复），
 *       在指定时间戳超过分区最大时间时自动弹性降级至最新位移 (LEO)；</li>
 *   <li>边界极值重置：支持将消费组位移安全重置至最早有效位移 (Earliest) 或最新日志位移 (Latest)；</li>
 *   <li>实时积压探测：实时拉取分区最新日志末端位移 (LEO) 与消费组提交位移差值，输出强类型 {@link ConsumerGroupLag}。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class KafkaOffsetManager {

    private static final String MQ_TYPE_KAFKA = "KAFKA";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaAdmin kafkaAdmin;
    private final ConsumerFactory<?, ?> consumerFactory;
    private final Duration timeout;
    private final Supplier<AdminClient> adminClientSupplier;

    /**
     * 基于 KafkaAdmin 构建位移治理门面（使用默认 10 秒超时）
     *
     * @param kafkaAdmin Spring KafkaAdmin 基础设施
     */
    public KafkaOffsetManager(KafkaAdmin kafkaAdmin) {
        this(kafkaAdmin, null, null);
    }

    /**
     * 基于 KafkaAdmin 与配置属性构建位移治理门面
     *
     * @param kafkaAdmin Spring KafkaAdmin 基础设施
     * @param properties Kafka 增强配置属性
     */
    public KafkaOffsetManager(KafkaAdmin kafkaAdmin, AtengKafkaProperties properties) {
        this(kafkaAdmin, properties, null);
    }

    /**
     * 基于完整参数构建位移治理门面
     *
     * @param kafkaAdmin Spring KafkaAdmin 基础设施（可为 null）
     * @param properties Kafka 增强配置属性（可为 null）
     * @param consumerFactory 消费工厂（作为连接属性备选回退，可为 null）
     */
    public KafkaOffsetManager(KafkaAdmin kafkaAdmin, AtengKafkaProperties properties, ConsumerFactory<?, ?> consumerFactory) {
        this.kafkaAdmin = kafkaAdmin;
        this.consumerFactory = consumerFactory;
        this.timeout = (properties != null && properties.getOffset() != null && properties.getOffset().getTimeout() != null)
                ? properties.getOffset().getTimeout()
                : DEFAULT_TIMEOUT;
        this.adminClientSupplier = null;
    }

    /**
     * 基于自定义 AdminClient 提供者与超时阈值构建（主要用于单测插桩或专用环境）
     *
     * @param adminClientSupplier AdminClient 实体提供者
     * @param timeout 操作硬超时时间
     */
    public KafkaOffsetManager(Supplier<AdminClient> adminClientSupplier, Duration timeout) {
        this.kafkaAdmin = null;
        this.consumerFactory = null;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.adminClientSupplier = adminClientSupplier;
    }

    // =========================================================================
    // 1. 位移查询与诊断 (Offset Query)
    // =========================================================================

    /**
     * 查询消费组在所有主题分区的当前已提交位移与元数据
     *
     * @param groupId 消费组 ID
     * @return 分区与其已提交位移元数据映射
     */
    public Map<TopicPartition, OffsetAndMetadata> getConsumerGroupOffsets(String groupId) {
        Assert.hasText(groupId, "groupId 不能为空白");
        try (AdminClient adminClient = createAdminClient()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = adminClient.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return offsets != null ? Collections.unmodifiableMap(offsets) : Collections.emptyMap();
        } catch (Exception ex) {
            log.error("[MQ-OFFSET-QUERY-FAILED] 查询消费组全量位移失败: groupId={}, error={}", groupId, ex.getMessage(), ex);
            throw new MessageDeliveryException(MQ_TYPE_KAFKA, null, null, "查询消费组位移失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 查询消费组在指定主题各分区的当前已提交位移与元数据
     *
     * @param groupId 消费组 ID
     * @param topic 目标主题名称
     * @return 过滤后指定主题的分区位移映射
     */
    public Map<TopicPartition, OffsetAndMetadata> getConsumerGroupOffsets(String groupId, String topic) {
        Assert.hasText(groupId, "groupId 不能为空白");
        Assert.hasText(topic, "topic 不能为空白");
        Map<TopicPartition, OffsetAndMetadata> allOffsets = getConsumerGroupOffsets(groupId);
        if (CollectionUtils.isEmpty(allOffsets)) {
            return Collections.emptyMap();
        }
        Map<TopicPartition, OffsetAndMetadata> filtered = new HashMap<>();
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : allOffsets.entrySet()) {
            if (entry.getKey() != null && topic.equals(entry.getKey().topic())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(filtered);
    }

    // =========================================================================
    // 2. 位移回拨与重置 (Offset Reset & Rewind)
    // =========================================================================

    /**
     * 将消费组在目标主题各分区的位移重置至最早有效位移 (Earliest Offset)
     *
     * @param groupId 消费组 ID
     * @param topic 目标主题名称
     * @return 重置后的各分区位移映射
     */
    public Map<TopicPartition, OffsetAndMetadata> resetOffsetsToEarliest(String groupId, String topic) {
        Assert.hasText(groupId, "groupId 不能为空白");
        Assert.hasText(topic, "topic 不能为空白");
        try (AdminClient adminClient = createAdminClient()) {
            List<TopicPartition> partitions = getTopicPartitions(adminClient, topic);

            Map<TopicPartition, OffsetSpec> offsetSpecs = new HashMap<>();
            for (TopicPartition tp : partitions) {
                offsetSpecs.put(tp, OffsetSpec.earliest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> listOffsetsResult =
                    adminClient.listOffsets(offsetSpecs).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            Map<TopicPartition, OffsetAndMetadata> offsetsToAlter = new HashMap<>();
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> entry : listOffsetsResult.entrySet()) {
                offsetsToAlter.put(entry.getKey(), new OffsetAndMetadata(entry.getValue().offset()));
            }

            adminClient.alterConsumerGroupOffsets(groupId, offsetsToAlter)
                    .all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            log.info("[MQ-OFFSET-RESET-EARLIEST] 成功将消费组位移重置至最早位移: groupId={}, topic={}, partitionsCount={}",
                    groupId, topic, offsetsToAlter.size());
            return Collections.unmodifiableMap(offsetsToAlter);
        } catch (Exception ex) {
            log.error("[MQ-OFFSET-RESET-FAILED] 重置消费组位移至最早失败: groupId={}, topic={}, error={}",
                    groupId, topic, ex.getMessage(), ex);
            throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null, "重置消费组位移至最早失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将消费组在目标主题各分区的位移重置至最新日志位移 (Latest Offset / LEO)
     *
     * @param groupId 消费组 ID
     * @param topic 目标主题名称
     * @return 重置后的各分区位移映射
     */
    public Map<TopicPartition, OffsetAndMetadata> resetOffsetsToLatest(String groupId, String topic) {
        Assert.hasText(groupId, "groupId 不能为空白");
        Assert.hasText(topic, "topic 不能为空白");
        try (AdminClient adminClient = createAdminClient()) {
            List<TopicPartition> partitions = getTopicPartitions(adminClient, topic);

            Map<TopicPartition, OffsetSpec> offsetSpecs = new HashMap<>();
            for (TopicPartition tp : partitions) {
                offsetSpecs.put(tp, OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> listOffsetsResult =
                    adminClient.listOffsets(offsetSpecs).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            Map<TopicPartition, OffsetAndMetadata> offsetsToAlter = new HashMap<>();
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> entry : listOffsetsResult.entrySet()) {
                offsetsToAlter.put(entry.getKey(), new OffsetAndMetadata(entry.getValue().offset()));
            }

            adminClient.alterConsumerGroupOffsets(groupId, offsetsToAlter)
                    .all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            log.info("[MQ-OFFSET-RESET-LATEST] 成功将消费组位移重置至最新位移: groupId={}, topic={}, partitionsCount={}",
                    groupId, topic, offsetsToAlter.size());
            return Collections.unmodifiableMap(offsetsToAlter);
        } catch (Exception ex) {
            log.error("[MQ-OFFSET-RESET-FAILED] 重置消费组位移至最新失败: groupId={}, topic={}, error={}",
                    groupId, topic, ex.getMessage(), ex);
            throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null, "重置消费组位移至最新失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将消费组在目标主题各分区的位移精准回退/重置至指定时间戳对应位移
     *
     * <p>若指定时间戳超过该分区最大消息时间（返回 -1），系统将自动降级使用该分区的最新位移 (LEO)，
     * 避免位移被置为非法负数导致消费崩溃。</p>
     *
     * @param groupId 消费组 ID
     * @param topic 目标主题名称
     * @param timestampMillis 目标时间戳（Unix 毫秒数，必须非负）
     * @return 重置后的各分区位移映射
     */
    public Map<TopicPartition, OffsetAndMetadata> resetOffsetsToTimestamp(String groupId, String topic, long timestampMillis) {
        Assert.hasText(groupId, "groupId 不能为空白");
        Assert.hasText(topic, "topic 不能为空白");
        Assert.isTrue(timestampMillis >= 0, "timestampMillis 必须大于等于 0");
        try (AdminClient adminClient = createAdminClient()) {
            List<TopicPartition> partitions = getTopicPartitions(adminClient, topic);

            Map<TopicPartition, OffsetSpec> offsetSpecs = new HashMap<>();
            for (TopicPartition tp : partitions) {
                offsetSpecs.put(tp, OffsetSpec.forTimestamp(timestampMillis));
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> timestampOffsets =
                    adminClient.listOffsets(offsetSpecs).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            // 针对时间戳超过分区最大时间返回 -1 的分区，准备降级为 latest
            Map<TopicPartition, OffsetSpec> fallbackSpecs = new HashMap<>();
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> entry : timestampOffsets.entrySet()) {
                if (entry.getValue() == null || entry.getValue().offset() == -1L) {
                    fallbackSpecs.put(entry.getKey(), OffsetSpec.latest());
                }
            }

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = Collections.emptyMap();
            if (!fallbackSpecs.isEmpty()) {
                latestOffsets = adminClient.listOffsets(fallbackSpecs).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            Map<TopicPartition, OffsetAndMetadata> offsetsToAlter = new HashMap<>();
            for (TopicPartition tp : partitions) {
                ListOffsetsResult.ListOffsetsResultInfo info = timestampOffsets.get(tp);
                long offset = (info != null) ? info.offset() : -1L;
                if (offset == -1L) {
                    ListOffsetsResult.ListOffsetsResultInfo latestInfo = latestOffsets.get(tp);
                    offset = (latestInfo != null) ? latestInfo.offset() : 0L;
                }
                offsetsToAlter.put(tp, new OffsetAndMetadata(offset));
            }

            adminClient.alterConsumerGroupOffsets(groupId, offsetsToAlter)
                    .all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            log.info("[MQ-OFFSET-RESET-TIMESTAMP] 成功将消费组位移按时间戳重置: groupId={}, topic={}, timestamp={}, partitionsCount={}",
                    groupId, topic, timestampMillis, offsetsToAlter.size());
            return Collections.unmodifiableMap(offsetsToAlter);
        } catch (Exception ex) {
            log.error("[MQ-OFFSET-RESET-FAILED] 按时间戳重置消费组位移失败: groupId={}, topic={}, timestamp={}, error={}",
                    groupId, topic, timestampMillis, ex.getMessage(), ex);
            throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null, "按时间戳重置消费组位移失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将消费组在目标主题各分区的位移精准回退/重置至指定时间戳对应位移 (Instant 强类型重载)
     *
     * @param groupId 消费组 ID
     * @param topic 目标主题名称
     * @param timestamp 目标时间戳 (Instant 对象，不能为空)
     * @return 重置后的各分区位移映射
     */
    public Map<TopicPartition, OffsetAndMetadata> resetOffsetsToTimestamp(String groupId, String topic, Instant timestamp) {
        Assert.notNull(timestamp, "timestamp 不能为空");
        return resetOffsetsToTimestamp(groupId, topic, timestamp.toEpochMilli());
    }

    // =========================================================================
    // 3. 实时积压探测 (Lag Monitoring)
    // =========================================================================

    /**
     * 探测消费组在目标主题下的实时积压情况 (Lag)
     *
     * <p>并行拉取主题所有分区的日志最新位移 (LEO) 与该消费组已提交位移，
     * 计算并输出全主题总积压及各分区明细快照。</p>
     *
     * @param groupId 消费组 ID
     * @param topic 目标主题名称
     * @return 强类型消费组积压监控数据模型
     */
    public ConsumerGroupLag getConsumerGroupLag(String groupId, String topic) {
        Assert.hasText(groupId, "groupId 不能为空白");
        Assert.hasText(topic, "topic 不能为空白");
        try (AdminClient adminClient = createAdminClient()) {
            List<TopicPartition> partitions = getTopicPartitions(adminClient, topic);

            // 1. 获取各分区最新日志末端位移 (LEO)
            Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
            for (TopicPartition tp : partitions) {
                latestSpecs.put(tp, OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets =
                    adminClient.listOffsets(latestSpecs).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            // 2. 获取该消费组在全分区的已提交位移
            Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                    adminClient.listConsumerGroupOffsets(groupId)
                            .partitionsToOffsetAndMetadata()
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            // 3. 逐分区聚合计算 Lag
            List<ConsumerGroupLag.PartitionLag> partitionLags = new ArrayList<>();
            long totalLag = 0L;

            for (TopicPartition tp : partitions) {
                ListOffsetsResult.ListOffsetsResultInfo latestInfo = latestOffsets.get(tp);
                long logEndOffset = (latestInfo != null) ? latestInfo.offset() : 0L;

                OffsetAndMetadata committedInfo = (committedOffsets != null) ? committedOffsets.get(tp) : null;
                Long committedOffset = (committedInfo != null) ? committedInfo.offset() : null;

                ConsumerGroupLag.PartitionLag partitionLag = ConsumerGroupLag.PartitionLag.of(tp.partition(), committedOffset, logEndOffset);
                partitionLags.add(partitionLag);
                totalLag += partitionLag.lag();
            }

            // 按分区编号升序排列
            partitionLags.sort(Comparator.comparingInt(ConsumerGroupLag.PartitionLag::partition));

            ConsumerGroupLag result = new ConsumerGroupLag(groupId, topic, totalLag, partitionLags);
            log.info("[MQ-OFFSET-LAG] 查询消费组 Lag 成功: groupId={}, topic={}, totalLag={}, partitions={}",
                    groupId, topic, totalLag, partitionLags.size());
            return result;
        } catch (Exception ex) {
            log.error("[MQ-OFFSET-LAG-FAILED] 查询消费组 Lag 失败: groupId={}, topic={}, error={}", groupId, topic, ex.getMessage(), ex);
            throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null, "查询消费组积压 Lag 失败: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 内部私有辅助方法
    // =========================================================================

    /**
     * 获取目标主题的所有物理分区信息
     *
     * @param adminClient Kafka 底座管理客户端
     * @param topic 主题名称
     * @return 分区列表
     * @throws Exception 底层 RPC 异常
     */
    private List<TopicPartition> getTopicPartitions(AdminClient adminClient, String topic) throws Exception {
        TopicDescription description = adminClient.describeTopics(Collections.singletonList(topic))
                .allTopicNames()
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .get(topic);
        if (description == null || CollectionUtils.isEmpty(description.partitions())) {
            throw new IllegalArgumentException("主题 [" + topic + "] 分区列表为空或不存在");
        }
        return description.partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();
    }

    /**
     * 创建底座 AdminClient（供子类扩展或单测插桩）
     *
     * @return AdminClient 实例
     */
    protected AdminClient createAdminClient() {
        if (adminClientSupplier != null) {
            return adminClientSupplier.get();
        }
        if (kafkaAdmin != null && !CollectionUtils.isEmpty(kafkaAdmin.getConfigurationProperties())) {
            return AdminClient.create(kafkaAdmin.getConfigurationProperties());
        }
        if (consumerFactory != null && !CollectionUtils.isEmpty(consumerFactory.getConfigurationProperties())) {
            return AdminClient.create(consumerFactory.getConfigurationProperties());
        }
        throw new IllegalStateException("无法创建 AdminClient：未配置有效的 KafkaAdmin 或 ConsumerFactory 连接属性");
    }
}
