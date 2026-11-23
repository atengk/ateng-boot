package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 优先级 Quorum 订单消费者组件
 * 演示消费具有多级优先级的业务订单，支持提取 amqp_priority 消息属性
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Component
public class PriorityOrderConsumer {

    private final List<OrderCreateMessage> receivedOrders = new CopyOnWriteArrayList<>();
    private final List<Integer> receivedPriorities = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_PRIORITY_ORDER)
    public void onPriorityOrder(OrderCreateMessage order, @Header(name = "amqp_priority", required = false) Integer priority) {
        log.info("[CONSUMER-PRIORITY] 收到优先级订单: orderId={}, priority={}", order.orderId(), priority);
        this.receivedOrders.add(order);
        this.receivedPriorities.add(priority != null ? priority : 0);
    }

    public List<OrderCreateMessage> getReceivedOrders() {
        return this.receivedOrders;
    }

    public List<Integer> getReceivedPriorities() {
        return this.receivedPriorities;
    }

    public void clear() {
        this.receivedOrders.clear();
        this.receivedPriorities.clear();
    }
}
