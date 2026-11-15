package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
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

    /**
     * 同步发送标准订单消息（默认 tag 为 create）
     *
     * @param order 订单消息实体
     * @return 发送回执
     */
    public SendReceipt sendSync(OrderMessage order) {
        return sendSync(order, "create");
    }

    /**
     * 同步发送指定标签的标准订单消息
     *
     * @param order 订单消息实体
     * @param tag   业务标签
     * @return 发送回执
     */
    public SendReceipt sendSync(OrderMessage order, String tag) {
        return sendSync(order, tag, null);
    }

    /**
     * 同步发送标准订单消息并附加 SQL92 用户属性
     *
     * @param order          订单消息实体
     * @param tag            业务标签
     * @param userProperties 用户自定义过滤属性
     * @return 发送回执
     */
    public SendReceipt sendSync(OrderMessage order, String tag, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        log.info("[STANDARD-SCENARIO] 同步发送订单消息: orderId={}, tag={}", order.getOrderId(), tag);
        return rocketmqTemplate.send(TradeRocketmqProducer.TOPIC_ORDER, tag, order.getOrderId(), order, userProperties);
    }

    /**
     * 异步发送标准订单消息
     *
     * @param order 订单消息实体
     * @return 异步发送回执
     */
    public CompletableFuture<SendReceipt> sendAsync(OrderMessage order) {
        return sendAsync(order, null);
    }

    /**
     * 异步发送标准订单消息并附加 SQL92 用户属性
     *
     * @param order          订单消息实体
     * @param userProperties 用户自定义过滤属性
     * @return 异步发送回执
     */
    public CompletableFuture<SendReceipt> sendAsync(OrderMessage order, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        log.info("[STANDARD-SCENARIO] 异步发送订单消息: orderId={}", order.getOrderId());
        return rocketmqTemplate.sendAsync(TradeRocketmqProducer.TOPIC_ORDER, "create", order.getOrderId(), order, userProperties);
    }

    /**
     * 单向发送订单埋点消息
     *
     * @param order 订单消息实体
     */
    public void sendOneway(OrderMessage order) {
        Assert.notNull(order, "order 不能为空");
        log.info("[STANDARD-SCENARIO] 单向发送订单埋点消息: orderId={}", order.getOrderId());
        rocketmqTemplate.sendOneway(TradeRocketmqProducer.TOPIC_ORDER, "oneway", order.getOrderId(), order);
    }

    /**
     * 顺序发送局部保序消息
     *
     * @param order        订单消息实体
     * @param messageGroup 顺序分组键
     * @return 发送回执
     */
    public SendReceipt sendFifo(OrderMessage order, String messageGroup) {
        Assert.notNull(order, "order 不能为空");
        log.info("[STANDARD-SCENARIO] 顺序发送局部保序消息: orderId={}, messageGroup={}", order.getOrderId(), messageGroup);
        return rocketmqTemplate.sendOrderly(TradeRocketmqProducer.TOPIC_FIFO, "fifo", order.getOrderId(), order, messageGroup);
    }

    /**
     * 发送携带 SQL92 自定义属性过滤消息
     *
     * @param order  订单消息实体
     * @param amount 订单金额
     * @return 发送回执
     */
    public SendReceipt sendSqlFiltered(OrderMessage order, double amount) {
        Assert.notNull(order, "order 不能为空");
        log.info("[STANDARD-SCENARIO] 发送携带 SQL92 自定义属性消息: orderId={}, amount={}", order.getOrderId(), amount);
        return rocketmqTemplate.send(TradeRocketmqProducer.TOPIC_SQL, "sql", order.getOrderId(), order, Map.of("amount", String.valueOf((long) amount)));
    }

    /**
     * 批量发送订单消息
     *
     * @param orders 订单列表
     * @param tag    业务标签
     * @return 按输入顺序对齐的发送回执列表
     */
    public List<SendReceipt> sendBatch(List<OrderMessage> orders, String tag) {
        return sendBatch(orders, tag, null);
    }

    /**
     * 批量发送订单消息并附加统一 SQL92 用户属性
     *
     * @param orders         订单列表
     * @param tag            业务标签
     * @param userProperties 用户自定义过滤属性
     * @return 按输入顺序对齐的发送回执列表
     */
    public List<SendReceipt> sendBatch(
            List<OrderMessage> orders,
            String tag,
            Map<String, String> userProperties) {
        log.info("[STANDARD-SCENARIO] 批量发送订单消息: size={}, tag={}", orders != null ? orders.size() : 0, tag);
        return rocketmqTemplate.sendBatch(TradeRocketmqProducer.TOPIC_ORDER, tag, orders, userProperties);
    }

    /**
     * 异步批量发送订单消息并附加统一 SQL92 用户属性
     *
     * @param orders         订单列表
     * @param tag            业务标签
     * @param userProperties 用户自定义过滤属性
     * @return 异步发送回执列表 Future
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(
            List<OrderMessage> orders,
            String tag,
            Map<String, String> userProperties) {
        log.info("[STANDARD-SCENARIO] 异步批量发送订单消息: size={}, tag={}", orders != null ? orders.size() : 0, tag);
        return rocketmqTemplate.sendBatchAsync(TradeRocketmqProducer.TOPIC_ORDER, tag, orders, userProperties);
    }
}
