package io.github.atengk.boot.rabbitmq.callback;

import org.springframework.amqp.core.ReturnedMessage;

/**
 * RabbitMQ 无法路由退回消息处理回调 SPI
 * 当消息投递至 Broker 但因 NO_ROUTE 等原因无法匹配任何队列时触发
 *
 * @author Ateng
 * @since 2026-10-21
 */
@FunctionalInterface
public interface RabbitUnroutableMessageCallback {

    /**
     * 消息不可路由退回回调
     *
     * @param returned 退回消息及元数据
     */
    void onUnroutableMessage(ReturnedMessage returned);
}
