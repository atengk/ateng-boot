package io.github.atengk.boot.rabbitmq.manager;

import java.util.Collections;
import java.util.List;

/**
 * RabbitMQ 监听容器运行状态快照视图
 *
 * @param listenerId          监听容器注册 ID
 * @param queueNames          绑定的队列名称列表 (不可变视图)
 * @param running             当前是否处于运行状态
 * @param autoStartup         是否随容器启动自动运行
 * @param activeConsumerCount 当前活跃消费者线程数
 * @param prefetchCount       当前通道预取数量
 * @param containerType       底层容器实现类型短名
 * @author Ateng
 * @since 2026-11-15
 */
public record RabbitContainerStatus(
        String listenerId,
        List<String> queueNames,
        boolean running,
        boolean autoStartup,
        int activeConsumerCount,
        int prefetchCount,
        String containerType
) {

    public RabbitContainerStatus {
        queueNames = queueNames == null ? Collections.emptyList() : List.copyOf(queueNames);
    }
}
