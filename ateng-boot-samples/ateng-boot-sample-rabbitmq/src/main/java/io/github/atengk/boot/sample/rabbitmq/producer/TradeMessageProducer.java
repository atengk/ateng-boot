package io.github.atengk.boot.sample.rabbitmq.producer;

import io.github.atengk.boot.rabbitmq.template.RabbitReliableTemplate;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderTimeoutMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 交易与系统消息生产者组件
 * 演示使用 RabbitReliableTemplate 进行同步阻塞可靠投递、异步投递与延迟投递
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeMessageProducer {

    private final RabbitReliableTemplate rabbitReliableTemplate;

    /**
     * 同步可靠投递：创建订单业务消息 (等待 Broker ACK)
     *
     * @param message 订单创建消息
     */
    public void sendOrderCreate(OrderCreateMessage message) {
        log.info("[PRODUCER] 开始同步投递订单创建消息: orderId={}", message.orderId());
        rabbitReliableTemplate.sendReliable(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE,
                message,
                message.orderId(),
                Duration.ofSeconds(3)
        );
        log.info("[PRODUCER] 订单创建消息投递成功并收到 Broker Confirm ACK: orderId={}", message.orderId());
    }

    /**
     * 异步非阻塞投递：创建订单业务消息
     *
     * @param message 订单创建消息
     * @return Confirm 异步 Future
     */
    public CompletableFuture<CorrelationData.Confirm> sendOrderCreateAsync(OrderCreateMessage message) {
        log.info("[PRODUCER] 开始异步投递订单创建消息: orderId={}", message.orderId());
        return rabbitReliableTemplate.sendReliableAsync(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE,
                message,
                message.orderId()
        );
    }

    /**
     * 层次通配路由投递：系统错误日志
     *
     * @param routingKey 动态通配路由键 (如 log.error.trade)
     * @param message    日志载荷
     */
    public void sendLogError(String routingKey, LogMessage message) {
        rabbitReliableTemplate.sendReliable(
                RabbitScenarioTopologyConfig.EXCHANGE_LOG_TOPIC,
                routingKey,
                message,
                message.serviceName()
        );
    }

    /**
     * 延迟队列投递：订单超时自动取消
     *
     * @param message 超时取消消息
     * @param delay   延迟时长
     */
    public void sendOrderDelay(OrderTimeoutMessage message, Duration delay) {
        log.info("[PRODUCER] 投递延迟订单消息: orderId={}, delay={}", message.orderId(), delay);
        rabbitReliableTemplate.sendDelayed(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DELAY,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_TIMEOUT,
                message,
                message.orderId(),
                delay
        );
    }

    /**
     * 业务队列投递：用于测试毒丸与死信流转
     */
    public void sendBizMessage(String bizKey, Object payload) {
        rabbitReliableTemplate.sendReliable(
                RabbitScenarioTopologyConfig.EXCHANGE_BIZ_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_BIZ,
                payload,
                bizKey
        );
    }

    /**
     * 投递至幂等测试队列
     */
    public void sendIdempotentMessage(String bizKey, Object payload) {
        rabbitReliableTemplate.sendReliable(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_IDEMPOTENT,
                payload,
                bizKey
        );
    }

    /**
     * 批量可靠投递：订单列表并发异步发送并聚合等待所有 Broker ACK
     *
     * @param orders 批量订单消息列表
     */
    public void sendBatchOrders(java.util.List<OrderCreateMessage> orders) {
        log.info("[PRODUCER] 开始批量投递订单创建消息: count={}", orders.size());
        rabbitReliableTemplate.sendBatchReliable(
                RabbitScenarioTopologyConfig.EXCHANGE_BATCH_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_BATCH_ORDER,
                orders
        );
        log.info("[PRODUCER] 批量订单消息全部收到 Broker Confirm ACK: count={}", orders.size());
    }

    /**
     * 优先级可靠投递：投递高/低优先级订单消息
     *
     * @param order    订单创建消息
     * @param priority 消息优先级 (0-255，对应队列 x-max-priority)
     */
    public void sendPriorityOrder(OrderCreateMessage order, int priority) {
        log.info("[PRODUCER] 投递优先级订单消息: orderId={}, priority={}", order.orderId(), priority);
        rabbitReliableTemplate.sendReliableWithPriority(
                RabbitScenarioTopologyConfig.EXCHANGE_PRIORITY_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_PRIORITY_ORDER,
                order,
                priority,
                order.orderId()
        );
    }

    /**
     * Fanout 广播投递：向所有动态绑定的匿名临时队列分发
     *
     * @param message 广播消息载荷
     */
    public void sendBroadcast(Object message) {
        log.info("[PRODUCER] 发起 Fanout 临时广播: message={}", message);
        rabbitReliableTemplate.sendReliable(
                RabbitScenarioTopologyConfig.EXCHANGE_BROADCAST_FANOUT,
                "",
                message
        );
    }
}
