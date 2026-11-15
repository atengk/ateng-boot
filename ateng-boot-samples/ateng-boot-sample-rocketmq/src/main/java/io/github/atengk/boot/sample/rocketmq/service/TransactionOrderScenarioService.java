package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionExecutor;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.checker.SampleOrderTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单分布式事务与本地事务感知场景业务服务
 *
 * <p>演示 2PC 原生分布式事务半消息（COMMIT/ROLLBACK）、事务回查分发以及单条/批量本地事务提交感知发信（sendAfterCommit / sendBatchAfterCommit）。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionOrderScenarioService {

    private static final String DEFAULT_TAG = "create";

    private final RocketmqTemplate rocketmqTemplate;
    private final SampleOrderTransactionChecker orderTransactionChecker;

    @Getter
    private final List<String> localDbOrders = new CopyOnWriteArrayList<>();

    /**
     * 本地事务提交后发信（保证本地事务成功后才投递消息，避免脏数据与幽灵消息）
     *
     * @param order 订单消息实体
     */
    @Transactional
    public void createOrderWithSendAfterCommit(OrderMessage order) {
        createOrderWithSendAfterCommit(order, DEFAULT_TAG, null);
    }

    /**
     * 本地事务提交后发信（支持自定义标签与 SQL92 属性）
     *
     * @param order          订单消息实体
     * @param tag            业务标签（为空时回退至 create）
     * @param userProperties SQL92 用户扩展属性
     */
    @Transactional
    public void createOrderWithSendAfterCommit(OrderMessage order, String tag, Map<String, String> userProperties) {
        Assert.notNull(order, "order 不能为空");
        String effectiveTag = StringUtils.hasText(tag) ? tag.trim() : DEFAULT_TAG;
        log.info("[LOCAL-DB] 模拟本地数据库写入订单: orderId={}", order.getOrderId());
        localDbOrders.add(order.getOrderId());
        rocketmqTemplate.sendAfterCommit(TradeRocketmqProducer.TOPIC_ORDER, effectiveTag, order.getOrderId(), order, userProperties);
    }

    /**
     * 本地事务回滚（事务回滚时，sendAfterCommit 挂载的钩子被自动安全丢弃）
     *
     * @param order 订单消息实体
     */
    @Transactional
    public void createOrderWithRollback(OrderMessage order) {
        Assert.notNull(order, "order 不能为空");
        log.info("[LOCAL-DB] 模拟本地数据库写入订单（将发生回滚）: orderId={}", order.getOrderId());
        localDbOrders.add(order.getOrderId());
        rocketmqTemplate.sendAfterCommit(TradeRocketmqProducer.TOPIC_ORDER, DEFAULT_TAG, order.getOrderId(), order);
        throw new IllegalStateException("模拟业务校验失败，触发本地事务回滚: orderId=" + order.getOrderId());
    }

    /**
     * 本地事务提交后批量发信（保证批内订单入库事务成功后才逐条投递并隔离单条故障）
     *
     * @param orders         订单消息列表
     * @param tag            业务标签（为空时回退至 create）
     * @param userProperties SQL92 用户扩展属性
     */
    @Transactional
    public void createOrdersWithSendBatchAfterCommit(
            List<OrderMessage> orders,
            String tag,
            Map<String, String> userProperties) {
        Assert.notNull(orders, "orders 不能为空");
        String effectiveTag = StringUtils.hasText(tag) ? tag.trim() : DEFAULT_TAG;
        log.info("[LOCAL-DB] 模拟本地数据库批量写入订单: count={}, tag={}", orders.size(), effectiveTag);
        for (OrderMessage order : orders) {
            Assert.notNull(order, "订单列表中存在 null 元素");
            localDbOrders.add(order.getOrderId());
        }
        rocketmqTemplate.sendBatchAfterCommit(TradeRocketmqProducer.TOPIC_ORDER, effectiveTag, orders, userProperties);
    }

    /**
     * 本地事务批量回滚（事务回滚时，sendBatchAfterCommit 挂载的批量同步钩子被自动安全丢弃）
     *
     * @param orders         订单消息列表
     * @param tag            业务标签（为空时回退至 create）
     * @param userProperties SQL92 用户扩展属性
     */
    @Transactional
    public void createOrdersWithBatchRollback(
            List<OrderMessage> orders,
            String tag,
            Map<String, String> userProperties) {
        createOrdersWithSendBatchAfterCommit(orders, tag, userProperties);
        throw new IllegalStateException("模拟批量业务校验失败，触发本地事务回滚: count=" + orders.size());
    }

    /**
     * 2PC 原生分布式事务半消息发送与本地事务决断
     */
    public SendReceipt createOrderWith2PcTransaction(OrderMessage order, boolean success) {
        RocketmqLocalTransactionExecutor executor = receipt -> {
            log.info("[2PC-EXECUTE] 收到半消息发送回执: messageId={}, 开始执行本地事务", receipt.getMessageId());
            if (success) {
                localDbOrders.add(order.getOrderId());
                orderTransactionChecker.markCommitted(order.getOrderId());
                log.info("[2PC-EXECUTE] 本地事务执行成功，决断 COMMIT: orderId={}", order.getOrderId());
                return TransactionResolution.COMMIT;
            } else {
                orderTransactionChecker.markRolledBack(order.getOrderId());
                log.warn("[2PC-EXECUTE] 本地事务执行失败，决断 ROLLBACK: orderId={}", order.getOrderId());
                return TransactionResolution.ROLLBACK;
            }
        };

        return rocketmqTemplate.sendTransaction(
                TradeRocketmqProducer.TOPIC_TX,
                "tx",
                order.getOrderId(),
                order,
                executor
        );
    }

    public void clear() {
        localDbOrders.clear();
    }
}
