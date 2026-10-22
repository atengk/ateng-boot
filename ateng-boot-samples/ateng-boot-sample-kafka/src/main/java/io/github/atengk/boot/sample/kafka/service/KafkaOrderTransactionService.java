package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.repository.MockOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单事务场景服务（演示 sendAfterCommit 事务感知投递与回滚防幽灵消息）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaOrderTransactionService {

    private final KafkaReliableTemplate kafkaReliableTemplate;
    private final MockOrderRepository mockOrderRepository;

    @Transactional
    public void createOrderWithSendAfterCommit(OrderMessage order) {
        // 1. 本地数据库模拟落库
        log.info("[DB-SAVE] 订单数据落库成功: orderId={}", order.getOrderId());
        mockOrderRepository.save(order.getOrderId());

        // 2. 挂载事务提交后异步发信钩子
        kafkaReliableTemplate.sendAfterCommit(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);
    }

    @Transactional
    public void createOrderWithRollback(OrderMessage order) {
        // 1. 本地数据库模拟落库
        mockOrderRepository.save(order.getOrderId());

        // 2. 挂载事务提交后异步发信钩子
        kafkaReliableTemplate.sendAfterCommit(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);

        // 3. 模拟异常触发本地事务回滚
        throw new IllegalStateException("模拟业务校验失败，触发事务回滚: orderId=" + order.getOrderId());
    }

    public List<String> getLocalDbOrders() {
        return mockOrderRepository.findAll();
    }

    public void clear() {
        mockOrderRepository.clear();
    }
}
