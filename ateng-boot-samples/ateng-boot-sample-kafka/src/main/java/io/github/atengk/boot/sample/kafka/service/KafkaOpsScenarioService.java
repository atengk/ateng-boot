package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.kafka.manager.KafkaContainerManager;
import io.github.atengk.boot.kafka.manager.KafkaOffsetManager;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Kafka 运维与运行时治理场景服务
 *
 * <p>演示位移治理回拨 (KafkaOffsetManager)、实时消费积压探测 (getConsumerGroupLag)
 * 以及运行期消费容器动态流控削峰 (KafkaContainerManager)。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaOpsScenarioService {

    private final ObjectProvider<KafkaOffsetManager> offsetManagerProvider;
    private final ObjectProvider<KafkaContainerManager> containerManagerProvider;

    /**
     * 查询消费组当前提交位移与元数据
     */
    public Map<TopicPartition, OffsetAndMetadata> getConsumerGroupOffsets(String groupId) {
        KafkaOffsetManager offsetManager = offsetManagerProvider.getIfAvailable();
        if (offsetManager == null) {
            return Map.of();
        }
        return offsetManager.getConsumerGroupOffsets(groupId);
    }

    /**
     * 按时间戳回溯重置消费位移
     */
    public void rewindOffsetsToTimestamp(String groupId, String topic, Instant timestamp) {
        KafkaOffsetManager offsetManager = offsetManagerProvider.getIfAvailable();
        if (offsetManager != null) {
            offsetManager.resetOffsetsToTimestamp(groupId, topic, timestamp);
        }
    }

    /**
     * 回拨消费位移至起始点 earliest
     */
    public void rewindOffsetsToEarliest(String groupId, String topic) {
        KafkaOffsetManager offsetManager = offsetManagerProvider.getIfAvailable();
        if (offsetManager != null) {
            offsetManager.resetOffsetsToEarliest(groupId, topic);
        }
    }

    /**
     * 推进消费位移至最新日志末端 latest
     */
    public void rewindOffsetsToLatest(String groupId, String topic) {
        KafkaOffsetManager offsetManager = offsetManagerProvider.getIfAvailable();
        if (offsetManager != null) {
            offsetManager.resetOffsetsToLatest(groupId, topic);
        }
    }

    /**
     * 实时查询消费组指定主题积压 Lag
     */
    public ConsumerGroupLag getConsumerGroupLag(String groupId, String topic) {
        KafkaOffsetManager offsetManager = offsetManagerProvider.getIfAvailable();
        if (offsetManager == null) {
            return null;
        }
        return offsetManager.getConsumerGroupLag(groupId, topic);
    }

    /**
     * 动态暂停指定 Topic 或 Listener ID 的消费拉取
     */
    public void pauseConsumer(String idOrTopic) {
        KafkaContainerManager containerManager = containerManagerProvider.getIfAvailable();
        if (containerManager != null) {
            containerManager.pause(idOrTopic);
        }
    }

    /**
     * 动态恢复指定 Topic 或 Listener ID 的消费拉取
     */
    public void resumeConsumer(String idOrTopic) {
        KafkaContainerManager containerManager = containerManagerProvider.getIfAvailable();
        if (containerManager != null) {
            containerManager.resume(idOrTopic);
        }
    }

    /**
     * 查询指定消费者容器是否处于暂停状态
     */
    public boolean isConsumerPaused(String idOrTopic) {
        KafkaContainerManager containerManager = containerManagerProvider.getIfAvailable();
        if (containerManager == null) {
            return false;
        }
        return containerManager.isPaused(idOrTopic);
    }

    /**
     * 查询指定消费者容器是否正在运行
     */
    public boolean isConsumerRunning(String idOrTopic) {
        KafkaContainerManager containerManager = containerManagerProvider.getIfAvailable();
        if (containerManager == null) {
            return false;
        }
        return containerManager.isRunning(idOrTopic);
    }
}
