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
 * 事务消息监听器（演示 2PC 分布式事务半消息提交后的下游消费）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class OrderTransactionConsumer {

    @Getter
    private final List<OrderMessage> receivedTxOrders = new CopyOnWriteArrayList<>();

    @RocketmqListener(
            topic = "sample_tx_topic",
            consumerGroup = "sample_tx_group",
            tag = "tx"
    )
    public void onTxOrder(OrderMessage order, MessageView messageView) {
        log.info("[SAMPLE-TX-CONSUME] 接收事务消息: orderId={}, tag={}",
                order.getOrderId(),
                messageView.getTag().orElse(""));

        receivedTxOrders.add(order);
    }

    public void clear() {
        receivedTxOrders.clear();
    }
}
