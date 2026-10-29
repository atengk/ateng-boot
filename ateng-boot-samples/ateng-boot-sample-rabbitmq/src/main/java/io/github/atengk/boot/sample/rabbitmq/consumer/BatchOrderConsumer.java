package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 批量订单削峰消费者组件
 * 演示使用削峰消费模式 (@RabbitListener 接收 OrderCreateMessage) 进行大流量微批消费
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Component
public class BatchOrderConsumer {

    private final List<OrderCreateMessage> allReceivedOrders = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_BATCH_ORDER)
    public void onBatch(OrderCreateMessage order) {
        log.info("[CONSUMER-BATCH] 收到批量订单消费消息: orderId={}", order.orderId());
        this.allReceivedOrders.add(order);
    }

    public List<OrderCreateMessage> getAllReceivedOrders() {
        return this.allReceivedOrders;
    }

    public void clear() {
        this.allReceivedOrders.clear();
    }
}
