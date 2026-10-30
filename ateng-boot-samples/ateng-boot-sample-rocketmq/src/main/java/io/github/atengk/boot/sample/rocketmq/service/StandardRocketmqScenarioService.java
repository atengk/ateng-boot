package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 标准消息收发场景业务服务
 *
 * <p>演示同步可靠发信、异步非阻塞回调、单向日志发信、FIFO 顺序消息以及 SQL92 属性过滤发信。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardRocketmqScenarioService {

    private final RocketmqTemplate rocketmqTemplate;

    public SendReceipt sendSync(OrderMessage order) {
        return sendSync(order, "create");
    }

    public SendReceipt sendSync(OrderMessage order, String tag) {
        log.info("[STANDARD-SCENARIO] 同步发送订单消息: orderId={}, tag={}", order.getOrderId(), tag);
        return rocketmqTemplate.send(TradeRocketmqProducer.TOPIC_ORDER, tag, order.getOrderId(), order);
    }

    public CompletableFuture<SendReceipt> sendAsync(OrderMessage order) {
        log.info("[STANDARD-SCENARIO] 异步发送订单消息: orderId={}", order.getOrderId());
        return rocketmqTemplate.sendAsync(TradeRocketmqProducer.TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public void sendOneway(OrderMessage order) {
        log.info("[STANDARD-SCENARIO] 单向发送订单埋点消息: orderId={}", order.getOrderId());
        rocketmqTemplate.sendOneway(TradeRocketmqProducer.TOPIC_ORDER, "oneway", order.getOrderId(), order);
    }

    public SendReceipt sendFifo(OrderMessage order, String messageGroup) {
        log.info("[STANDARD-SCENARIO] 顺序发送局部保序消息: orderId={}, messageGroup={}", order.getOrderId(), messageGroup);
        return rocketmqTemplate.sendOrderly(TradeRocketmqProducer.TOPIC_FIFO, "fifo", order.getOrderId(), order, messageGroup);
    }

    public SendReceipt sendSqlFiltered(OrderMessage order, double amount) {
        log.info("[STANDARD-SCENARIO] 发送携带 SQL92 自定义属性消息: orderId={}, amount={}", order.getOrderId(), amount);
        return rocketmqTemplate.send(TradeRocketmqProducer.TOPIC_SQL, null, order.getOrderId(), order, Map.of("amount", String.valueOf(amount)));
    }
}
