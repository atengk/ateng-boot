package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 批量与延时定时消息场景业务服务
 *
 * <p>演示批量发信（sendBatch）、延时定时发信（sendDelayed/sendDeliverAt）以及延时关单消息原生精准撤回（recallMessage）。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDelayScenarioService {

    private final RocketmqTemplate rocketmqTemplate;

    public List<SendReceipt> sendBatch(List<OrderMessage> orders) {
        log.info("[BATCH-SCENARIO] 批量发送订单消息: count={}", orders.size());
        return rocketmqTemplate.sendBatch(TradeRocketmqProducer.TOPIC_ORDER, "batch", orders);
    }

    public CompletableFuture<List<SendReceipt>> sendBatchAsync(List<OrderMessage> orders) {
        log.info("[BATCH-SCENARIO] 异步批量发送订单消息: count={}", orders.size());
        return rocketmqTemplate.sendBatchAsync(TradeRocketmqProducer.TOPIC_ORDER, "batch", orders);
    }

    public SendReceipt sendDelayed(OrderMessage order, Duration delay) {
        log.info("[DELAY-SCENARIO] 发送延时订单消息: orderId={}, delay={}", order.getOrderId(), delay);
        return rocketmqTemplate.sendDelayed(TradeRocketmqProducer.TOPIC_DELAY, "delay", order.getOrderId(), order, delay);
    }

    public SendReceipt sendDeliverAt(OrderMessage order, Instant deliverAt) {
        log.info("[DELAY-SCENARIO] 发送指定绝对时间投递定时消息: orderId={}, deliverAt={}", order.getOrderId(), deliverAt);
        return rocketmqTemplate.sendDeliverAt(TradeRocketmqProducer.TOPIC_DELAY, "delay", order.getOrderId(), order, deliverAt);
    }

    public void recallDelayedMessage(String topic, String recallHandle) {
        log.info("[DELAY-SCENARIO] 原生撤回未到期定时延时消息: topic={}, recallHandle={}", topic, recallHandle);
        rocketmqTemplate.recallMessage(topic, recallHandle);
    }
}
