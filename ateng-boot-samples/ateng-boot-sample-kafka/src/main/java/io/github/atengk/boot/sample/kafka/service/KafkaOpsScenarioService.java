package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.kafka.manager.KafkaContainerManager;
import io.github.atengk.boot.kafka.manager.KafkaOffsetManager;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag;
import io.github.atengk.boot.sample.kafka.dto.ContainerActionRequest;
import io.github.atengk.boot.sample.kafka.dto.OffsetResetRequest;
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
     * 启动指定 Topic 或 Listener ID 的消费容器
     */
    public void startConsumer(String idOrTopic) {
        KafkaContainerManager containerManager = containerManagerProvider.getIfAvailable();
        if (containerManager != null) {
            containerManager.start(idOrTopic);
        }
    }

    /**
     * 停止指定 Topic 或 Listener ID 的消费容器
     */
    public void stopConsumer(String idOrTopic) {
        KafkaContainerManager containerManager = containerManagerProvider.getIfAvailable();
        if (containerManager != null) {
            containerManager.stop(idOrTopic);
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

    /**
     * 执行位移重设统一业务调度与参数前置防御
     *
     * @param request 位移回拨请求
     */
    public void resetOffsets(OffsetResetRequest request) {
        BusinessAssert.notNull(request, "位移回拨请求对象不能为空");
        BusinessAssert.notNull(request.getResetType(), "重设策略类型不能为空");

        switch (request.getResetType()) {
            case EARLIEST -> rewindOffsetsToEarliest(request.getGroupId(), request.getTopic());
            case LATEST -> rewindOffsetsToLatest(request.getGroupId(), request.getTopic());
            case TIMESTAMP -> {
                BusinessAssert.isTrue(request.getTimestampMs() != null && request.getTimestampMs() > 0,
                        "重设策略为 TIMESTAMP 时，timestampMs 必须为有效正数时间戳");
                rewindOffsetsToTimestamp(request.getGroupId(), request.getTopic(), Instant.ofEpochMilli(request.getTimestampMs()));
            }
        }
    }

    /**
     * 执行容器动作统一调度
     *
     * @param request 容器管控请求
     */
    public void executeContainerAction(ContainerActionRequest request) {
        BusinessAssert.notNull(request, "容器管控请求对象不能为空");
        BusinessAssert.notNull(request.getAction(), "容器动作类型不能为空");

        switch (request.getAction()) {
            case PAUSE -> pauseConsumer(request.getContainerIdOrTopic());
            case RESUME -> resumeConsumer(request.getContainerIdOrTopic());
            case START -> startConsumer(request.getContainerIdOrTopic());
            case STOP -> stopConsumer(request.getContainerIdOrTopic());
        }
    }
}
