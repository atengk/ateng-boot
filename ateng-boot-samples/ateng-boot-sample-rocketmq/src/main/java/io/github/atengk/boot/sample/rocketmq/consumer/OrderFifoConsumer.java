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
 * 顺序消息监听器（演示 MessageGroup 严格保序消费）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class OrderFifoConsumer {

    @Getter
    private final List<OrderMessage> receivedFifoOrders = new CopyOnWriteArrayList<>();
    @Getter
    private final List<String> receivedMessageGroups = new CopyOnWriteArrayList<>();

    @RocketmqListener(
            topic = "sample_fifo_topic",
            consumerGroup = "sample_fifo_group",
            tag = "fifo"
    )
    public void onFifoOrder(OrderMessage order, MessageView messageView) {
        String messageGroup = messageView.getMessageGroup().orElse("");
        log.info("[SAMPLE-FIFO-CONSUME] 接收顺序订单: orderId={}, messageGroup={}",
                order.getOrderId(),
                messageGroup);

        receivedFifoOrders.add(order);
        receivedMessageGroups.add(messageGroup);
    }

    public void clear() {
        receivedFifoOrders.clear();
        receivedMessageGroups.clear();
    }
}
