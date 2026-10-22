package io.github.atengk.boot.sample.rocketmq.producer;

import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionExecutor;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 交易场景 RocketMQ 生产端业务组件
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeRocketmqProducer {

    public static final String TOPIC_ORDER = "sample_order_topic";
    public static final String TOPIC_DELAY = "sample_delay_topic";
    public static final String TOPIC_FIFO = "sample_fifo_topic";
    public static final String TOPIC_TX = "sample_tx_topic";
    public static final String TOPIC_SQL = "sample_sql_topic";

    private final RocketmqTemplate rocketmqTemplate;

    public SendReceipt sendOrder(OrderMessage order) {
        return rocketmqTemplate.send(TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public CompletableFuture<SendReceipt> sendOrderAsync(OrderMessage order) {
        return rocketmqTemplate.sendAsync(TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public SendReceipt sendOrderDelay(OrderMessage order, Duration delay) {
        return rocketmqTemplate.sendDelayed(TOPIC_DELAY, "delay", order.getOrderId(), order, delay);
    }

    public SendReceipt sendOrderDeliverAt(OrderMessage order, Instant deliverAt) {
        return rocketmqTemplate.sendDeliverAt(TOPIC_DELAY, "delay", order.getOrderId(), order, deliverAt);
    }

    public SendReceipt sendOrderFifo(OrderMessage order, String messageGroup) {
        return rocketmqTemplate.sendOrderly(TOPIC_FIFO, "fifo", order.getOrderId(), order, messageGroup);
    }

    public SendReceipt sendOrderTransaction(OrderMessage order, RocketmqLocalTransactionExecutor executor) {
        return rocketmqTemplate.sendTransaction(TOPIC_TX, "tx", order.getOrderId(), order, executor);
    }

    public void sendOrderAfterCommit(OrderMessage order) {
        rocketmqTemplate.sendAfterCommit(TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public SendReceipt sendOrderWithSqlProperties(OrderMessage order, Map<String, String> properties) {
        return rocketmqTemplate.send(TOPIC_SQL, null, order.getOrderId(), order, properties);
    }
}
