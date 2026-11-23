package io.github.atengk.boot.kafka.model;

import java.util.List;
import java.util.Objects;

/**
 * Kafka 消费监听容器运行期状态不可变快照视图
 *
 * @param listenerId           监听容器注册 ID
 * @param groupId              消费组 ID
 * @param topics               订阅主题列表（不可变非空视图）
 * @param running              当前是否处于运行状态
 * @param paused               当前是否处于暂停消费或待暂停请求状态
 * @param autoStartup          是否随应用启动自动运行
 * @param batchListener        是否为批量消费监听器
 * @param concurrency          配置并发消费者线程数
 * @param activeContainerCount 当前处于运行态的底层子容器实例数
 * @param assignedPartitions   当前已分配分区标识列表（如 {@code topic-0}，不可变非空视图）
 * @param containerType        底层监听容器实现类型短名
 * @author Ateng
 * @since 2026-11-15
 */
public record KafkaContainerStatus(
        String listenerId,
        String groupId,
        List<String> topics,
        boolean running,
        boolean paused,
        boolean autoStartup,
        boolean batchListener,
        int concurrency,
        int activeContainerCount,
        List<String> assignedPartitions,
        String containerType
) {

    /**
     * 规范构造函数：确保集合字段不可变与空安全
     */
    public KafkaContainerStatus {
        topics = topics == null ? List.of() : topics.stream().filter(Objects::nonNull).toList();
        assignedPartitions = assignedPartitions == null
                ? List.of()
                : assignedPartitions.stream().filter(Objects::nonNull).toList();
    }
}
