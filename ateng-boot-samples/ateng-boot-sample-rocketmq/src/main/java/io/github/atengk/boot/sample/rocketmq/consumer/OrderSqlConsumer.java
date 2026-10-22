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
 * SQL92 属性过滤监听器（演示服务端属性过滤表达式与高吞吐下推）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class OrderSqlConsumer {

    @Getter
    private final List<OrderMessage> receivedSqlOrders = new CopyOnWriteArrayList<>();

    @RocketmqListener(
            topic = "sample_sql_topic",
            consumerGroup = "sample_sql_group",
            sql92 = "amount > 100"
    )
    public void onSqlFilteredOrder(OrderMessage order, MessageView messageView) {
        log.info("[SAMPLE-SQL-CONSUME] 接收 SQL92 过滤消息: orderId={}, amount={}",
                order.getOrderId(),
                order.getAmount());

        receivedSqlOrders.add(order);
    }

    public void clear() {
        receivedSqlOrders.clear();
    }
}
