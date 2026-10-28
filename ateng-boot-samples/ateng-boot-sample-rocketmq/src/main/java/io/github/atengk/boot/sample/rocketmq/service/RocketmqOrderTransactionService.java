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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单事务场景服务（演示 sendAfterCommit 本地事务感知投递与 2PC 原生分布式事务半消息）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RocketmqOrderTransactionService {

    private final RocketmqTemplate rocketmqTemplate;
    private final SampleOrderTransactionChecker orderTransactionChecker;

    @Getter
    private final List<String> localDbOrders = new CopyOnWriteArrayList<>();

    /**
     * 本地事务提交后发信（保证本地事务成功后才投递消息，避免脏数据与幽灵消息）
     */
    @Transactional
    public void createOrderWithSendAfterCommit(OrderMessage order) {
        log.info("[LOCAL-DB] 模拟本地数据库写入订单: orderId={}", order.getOrderId());
        localDbOrders.add(order.getOrderId());

        rocketmqTemplate.sendAfterCommit(TradeRocketmqProducer.TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    /**
     * 本地事务回滚（事务回滚时，sendAfterCommit 挂载的钩子被自动安全丢弃）
     */
    @Transactional
    public void createOrderWithRollback(OrderMessage order) {
        log.info("[LOCAL-DB] 模拟本地数据库写入订单（将发生回滚）: orderId={}", order.getOrderId());
        localDbOrders.add(order.getOrderId());

        rocketmqTemplate.sendAfterCommit(TradeRocketmqProducer.TOPIC_ORDER, "create", order.getOrderId(), order);

        throw new IllegalStateException("模拟业务校验失败，触发本地事务回滚: orderId=" + order.getOrderId());
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
