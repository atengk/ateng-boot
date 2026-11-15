package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * 批量发送订单消息
     *
     * @param orders 订单列表
     * @return 按输入顺序对齐的发送回执列表
     */
    public List<SendReceipt> sendBatch(List<OrderMessage> orders) {
        return sendBatch(orders, null);
    }

    /**
     * 批量发送订单消息并附加统一 SQL92 用户属性
     *
     * @param orders         订单列表
     * @param userProperties 用户自定义过滤属性
     * @return 按输入顺序对齐的发送回执列表
     */
    public List<SendReceipt> sendBatch(List<OrderMessage> orders, Map<String, String> userProperties) {
        log.info("[BATCH-SCENARIO] 批量发送订单消息: count={}", orders != null ? orders.size() : 0);
        return rocketmqTemplate.sendBatch(TradeRocketmqProducer.TOPIC_ORDER, "batch", orders, userProperties);
    }

    /**
     * 异步批量发送订单消息
     *
     * @param orders 订单列表
     * @return 异步发送回执列表 Future
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(List<OrderMessage> orders) {
        return sendBatchAsync(orders, null);
    }

    /**
     * 异步批量发送订单消息并附加统一 SQL92 用户属性
     *
     * @param orders         订单列表
     * @param userProperties 用户自定义过滤属性
     * @return 异步发送回执列表 Future
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(List<OrderMessage> orders, Map<String, String> userProperties) {
        log.info("[BATCH-SCENARIO] 异步批量发送订单消息: count={}", orders != null ? orders.size() : 0);
        return rocketmqTemplate.sendBatchAsync(TradeRocketmqProducer.TOPIC_ORDER, "batch", orders, userProperties);
    }

    /**
     * 发送相对时长延时订单消息
     *
     * @param order 订单消息实体
     * @param delay 相对延时时长
     * @return 发送回执
     */
    public SendReceipt sendDelayed(OrderMessage order, Duration delay) {
        return sendDelayed(order, delay, null);
    }

    /**
     * 发送相对时长延时订单消息并附加 SQL92 用户属性
     *
     * @param order          订单消息实体
     * @param delay          相对延时时长
     * @param userProperties 用户自定义过滤属性
     * @return 发送回执
     */
    public SendReceipt sendDelayed(OrderMessage order, Duration delay, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        Assert.notNull(delay, "delay 不能为空");
        log.info("[DELAY-SCENARIO] 发送延时订单消息: orderId={}, delay={}", order.getOrderId(), delay);
        return rocketmqTemplate.sendDelayed(TradeRocketmqProducer.TOPIC_DELAY, "delay", order.getOrderId(), order, delay, userProperties);
    }

    /**
     * 异步发送相对时长延时订单消息并附加 SQL92 用户属性
     *
     * @param order          订单消息实体
     * @param delay          相对延时时长
     * @param userProperties 用户自定义过滤属性
     * @return 异步发送回执
     */
    public CompletableFuture<SendReceipt> sendDelayedAsync(OrderMessage order, Duration delay, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        Assert.notNull(delay, "delay 不能为空");
        log.info("[DELAY-SCENARIO] 异步发送延时订单消息: orderId={}, delay={}", order.getOrderId(), delay);
        return rocketmqTemplate.sendDelayedAsync(TradeRocketmqProducer.TOPIC_DELAY, "delay", order.getOrderId(), order, delay, userProperties);
    }

    /**
     * 发送指定绝对时间投递定时消息
     *
     * @param order     订单消息实体
     * @param deliverAt 目标投递时刻
     * @return 发送回执
     */
    public SendReceipt sendDeliverAt(OrderMessage order, Instant deliverAt) {
        return sendDeliverAt(order, deliverAt, null);
    }

    /**
     * 发送指定绝对时间投递定时消息并附加 SQL92 用户属性
     *
     * @param order          订单消息实体
     * @param deliverAt      目标投递时刻
     * @param userProperties 用户自定义过滤属性
     * @return 发送回执
     */
    public SendReceipt sendDeliverAt(OrderMessage order, Instant deliverAt, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        Assert.notNull(deliverAt, "deliverAt 不能为空");
        log.info("[DELAY-SCENARIO] 发送指定绝对时间投递定时消息: orderId={}, deliverAt={}", order.getOrderId(), deliverAt);
        return rocketmqTemplate.sendDeliverAt(TradeRocketmqProducer.TOPIC_DELAY, "delay", order.getOrderId(), order, deliverAt, userProperties);
    }

    /**
     * 异步发送指定绝对时间投递定时消息并附加 SQL92 用户属性
     *
     * @param order          订单消息实体
     * @param deliverAt      目标投递时刻
     * @param userProperties 用户自定义过滤属性
     * @return 异步发送回执
     */
    public CompletableFuture<SendReceipt> sendDeliverAtAsync(OrderMessage order, Instant deliverAt, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        Assert.notNull(deliverAt, "deliverAt 不能为空");
        log.info("[DELAY-SCENARIO] 异步发送指定绝对时间投递定时消息: orderId={}, deliverAt={}", order.getOrderId(), deliverAt);
        return rocketmqTemplate.sendDeliverAtAsync(TradeRocketmqProducer.TOPIC_DELAY, "delay", order.getOrderId(), order, deliverAt, userProperties);
    }

    /**
     * 原生撤回未到期定时/延时消息
     *
     * @param topic        主题名称
     * @param recallHandle 撤回句柄
     */
    public void recallDelayedMessage(String topic, String recallHandle) {
        log.info("[DELAY-SCENARIO] 原生撤回未到期定时延时消息: topic={}, recallHandle={}", topic, recallHandle);
        rocketmqTemplate.recallMessage(topic, recallHandle);
    }
}
