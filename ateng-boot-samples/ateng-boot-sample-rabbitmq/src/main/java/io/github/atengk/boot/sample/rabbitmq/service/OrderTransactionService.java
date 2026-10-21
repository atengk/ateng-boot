package io.github.atengk.boot.sample.rabbitmq.service;

import io.github.atengk.boot.rabbitmq.template.RabbitReliableTemplate;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单事务感知服务 (演示 sendAfterCommit 机制)
 * 验证事务提交后物理发信与事务回滚时零发信（杜绝幽灵消息 Ghost Message）
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final RabbitReliableTemplate rabbitReliableTemplate;
    private final AtomicInteger dbRecordCount = new AtomicInteger(0);

    /**
     * 正常下单流程：本地事务成功提交后，自动触发物理发信
     *
     * @param message 订单消息
     */
    @Transactional
    public void createOrderWithCommit(OrderCreateMessage message) {
        // 1. 模拟本地数据库写入
        dbRecordCount.incrementAndGet();
        log.info("[TX-SERVICE] 本地订单入库成功: orderId={}", message.orderId());

        // 2. 注册事务提交后发信钩子 (此时不发起物理网络 IO，等待 DB Commit 触发)
        rabbitReliableTemplate.sendAfterCommit(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE,
                message,
                message.orderId()
        );
        log.info("[TX-SERVICE] 已注册 sendAfterCommit 事务发信钩子: orderId={}", message.orderId());
    }

    /**
     * 异常下单流程：业务抛出异常触发本地事务回滚，发信钩子自动作废
     *
     * @param message 订单消息
     */
    @Transactional
    public void createOrderWithRollback(OrderCreateMessage message) {
        // 1. 模拟本地订单尝试入库
        dbRecordCount.incrementAndGet();
        log.info("[TX-SERVICE] 本地订单尝试入库 (即将回滚): orderId={}", message.orderId());

        // 2. 注册事务发信钩子
        rabbitReliableTemplate.sendAfterCommit(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE,
                message,
                message.orderId()
        );

        // 3. 模拟业务异常触发回滚
        throw new IllegalStateException("模拟库存扣减失败或网络超时，触发本地事务回滚: orderId=" + message.orderId());
    }

    public int getDbRecordCount() {
        return dbRecordCount.get();
    }
}
