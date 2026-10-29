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
 * 演示使用 Spring AMQP 批量模式 (@RabbitListener 接收 List<T>) 进行大流量微批消费
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Component
public class BatchOrderConsumer {

    private final List<List<OrderCreateMessage>> receivedBatches = new CopyOnWriteArrayList<>();
    private final List<OrderCreateMessage> allReceivedOrders = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_BATCH_ORDER)
    public void onBatch(List<OrderCreateMessage> orders) {
        log.info("[CONSUMER-BATCH] 收到批量订单消费消息: count={}", orders.size());
        this.receivedBatches.add(orders);
        this.allReceivedOrders.addAll(orders);
    }

    public List<List<OrderCreateMessage>> getReceivedBatches() {
        return this.receivedBatches;
    }

    public List<OrderCreateMessage> getAllReceivedOrders() {
        return this.allReceivedOrders;
    }

    public void clear() {
        this.receivedBatches.clear();
        this.allReceivedOrders.clear();
    }
}
