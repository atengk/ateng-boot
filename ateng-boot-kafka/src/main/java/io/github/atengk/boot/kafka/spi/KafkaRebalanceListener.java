package io.github.atengk.boot.kafka.spi;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;

import java.util.Collection;

/**
 * Kafka 消费端再均衡生命周期 SPI 扩展点
 *
 * <p>赋予上层应用在消费者发生协同重平衡（Rebalance）时的生命周期拦截能力，
 * 支持在分区分配（Assigned）或撤销（Revoked）时执行本地内存缓存同步、
 * 状态快照持久化、位移预提交（Offset Flush）或外部系统协调闭环。</p>
 *
 * <p>实现该接口的 Spring Bean 将被自动发现并挂载至所有受管监听容器工厂。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public interface KafkaRebalanceListener {

    /**
     * 当分区被重新分配给当前消费实例时回调
     *
     * @param consumer 原生 Kafka Consumer 实例
     * @param partitions 新分配的主题物理分区集合
     */
    default void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
    }

    /**
     * 当分区即将从当前消费实例撤销时回调（如消费者离开群组或新消费者加入触发再均衡）
     *
     * @param consumer 原生 Kafka Consumer 实例
     * @param partitions 即将撤销的主题物理分区集合
     */
    default void onPartitionsRevoked(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
    }

    /**
     * 当分区非正常丢失时回调（如由于 Heartbeat 超时导致消费者被 Coordinator 剔除）
     *
     * @param consumer 原生 Kafka Consumer 实例
     * @param partitions 丢失的主题物理分区集合
     */
    default void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
    }
}
