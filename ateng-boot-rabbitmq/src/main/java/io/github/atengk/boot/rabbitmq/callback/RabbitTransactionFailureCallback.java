package io.github.atengk.boot.rabbitmq.callback;

/**
 * RabbitMQ 事务提交后发信失败补偿回调 SPI
 * 当本地事务已成功提交，但后续异步网络发信失败时被触发，供业务方落库死信补发表或告警
 *
 * @author Ateng
 * @since 2026-10-21
 */
@FunctionalInterface
public interface RabbitTransactionFailureCallback {

    /**
     * 发信失败补偿回调
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    原始消息载荷
     * @param bizKey     业务唯一标识
     * @param cause      失败原因
     */
    void onFailure(String exchange, String routingKey, Object payload, String bizKey, Throwable cause);
}
