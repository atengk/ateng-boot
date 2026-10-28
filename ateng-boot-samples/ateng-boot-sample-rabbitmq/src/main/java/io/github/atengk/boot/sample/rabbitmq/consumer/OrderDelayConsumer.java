package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.OrderTimeoutMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 延迟消息消费者 (场景 C: 订单超时自动取消实测)
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
public class OrderDelayConsumer {

    private final List<OrderTimeoutMessage> timeoutOrders = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_ORDER_DELAY)
    public void onTimeout(OrderTimeoutMessage message) {
        log.info("[CONSUMER-DELAY] 收到订单超时未支付通知，执行自动取消履约: orderId={}, reason={}",
                message.orderId(), message.reason());
        timeoutOrders.add(message);
    }

    public List<OrderTimeoutMessage> getTimeoutOrders() {
        return timeoutOrders;
    }

    public void clear() {
        timeoutOrders.clear();
    }
}
