package io.github.atengk.boot.sample.rocketmq.consumer;

import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 延时消息监听器（演示 RocketMQ 5.x 毫秒级延时调度投递）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class OrderDelayConsumer {

    @Getter
    private final List<OrderMessage> receivedDelayOrders = new CopyOnWriteArrayList<>();

    @RocketmqListener(
            topic = "sample_delay_topic",
            consumerGroup = "sample_delay_group",
            tag = "delay"
    )
    public void onDelayOrder(OrderMessage order, MessageView messageView) {
        log.info("[SAMPLE-DELAY-CONSUME] 接收延时订单: orderId={}, deliveryTimestamp={}, tag={}",
                order.getOrderId(),
                messageView.getDeliveryTimestamp().orElse(null),
                messageView.getTag().orElse(""));

        receivedDelayOrders.add(order);
    }

    public void clear() {
        receivedDelayOrders.clear();
    }
}
