package io.github.atengk.boot.rabbitmq.manager;

/**
 * RabbitMQ 队列深度与在线消费者观测快照视图
 *
 * @param queueName     队列名称
 * @param messageCount  当前就绪堆积消息数
 * @param consumerCount 当前在线消费者数
 * @param exists        队列是否存在且可被服务端管理接口观测
 * @author Ateng
 * @since 2026-11-15
 */
public record RabbitQueueStatus(
        String queueName,
        long messageCount,
        int consumerCount,
        boolean exists
) {
}
