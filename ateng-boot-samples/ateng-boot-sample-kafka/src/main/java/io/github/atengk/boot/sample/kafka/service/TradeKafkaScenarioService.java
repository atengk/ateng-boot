package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.repository.MockOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 交易场景服务
 *
 * <p>演示 Key 分区保序同步发送、异步发信、物理分区定向投递、本地 DB 事务感知发信（sendAfterCommit）
 * 以及原生 Kafka 分布式事务执行（executeInTransaction）。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeKafkaScenarioService {

    private final KafkaReliableTemplate kafkaReliableTemplate;
    private final MockOrderRepository mockOrderRepository;

    /**
     * 同步按 Key 发送订单，基于 Murmur2 Hash 路由至固定分区
     */
    public SendResult<Object, Object> sendSyncOrder(OrderMessage order) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);
    }

    /**
     * 异步发送用户事件，返回 CompletableFuture 结果
     */
    public CompletableFuture<SendResult<Object, Object>> sendAsyncUserEvent(UserEventMessage event) {
        return kafkaReliableTemplate.sendReliableAsync(KafkaScenarioTopologyConfig.TOPIC_USER, event.getUserId(), event);
    }

    /**
     * 定向发送消息至指定物理分区
     */
    public SendResult<Object, Object> sendPartitionDirected(int partition, String key, Object payload) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, partition, key, payload);
    }

    /**
     * 批量可靠发送订单消息
     */
    public List<SendResult<Object, Object>> sendBatchOrders(List<OrderMessage> orders) {
        return kafkaReliableTemplate.sendBatch(KafkaScenarioTopologyConfig.TOPIC_BATCH, orders);
    }

    /**
     * 发送大体积二进制载荷（用于测试 Fail-Fast 限制）
     */
    public SendResult<Object, Object> sendLargePayload(byte[] payload) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, "large-key", payload);
    }

    /**
     * 本地事务成功提交后发射消息 (sendAfterCommit)
     */
    @Transactional
    public void createOrderWithSendAfterCommit(OrderMessage order) {
        log.info("[DB-SAVE] 订单数据落库成功: orderId={}", order.getOrderId());
        mockOrderRepository.save(order.getOrderId());
        kafkaReliableTemplate.sendAfterCommit(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);
    }

    /**
     * 本地事务回滚，阻断发信防止幽灵消息产生
     */
    @Transactional
    public void createOrderWithRollback(OrderMessage order) {
        mockOrderRepository.save(order.getOrderId());
        kafkaReliableTemplate.sendAfterCommit(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);
        throw new IllegalStateException("模拟业务异常，触发事务回滚: orderId=" + order.getOrderId());
    }

    /**
     * 原生 Kafka 分布式事务执行多主题原子写入
     */
    public <T> T executeInTransaction(KafkaOperations.OperationsCallback<Object, Object, T> callback) {
        return kafkaReliableTemplate.executeInTransaction(callback);
    }

    /**
     * 演示原子双写：订单创建与审计日志在一个 Kafka 原生事务内提交
     */
    public String executeTradeAtomicMultiSend(OrderMessage order, String auditPayload) {
        return kafkaReliableTemplate.executeInTransaction(operations -> {
            operations.send(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);
            operations.send(KafkaScenarioTopologyConfig.TOPIC_USER, order.getUserId(), auditPayload);
            return "SUCCESS";
        });
    }

    public List<String> getLocalDbOrders() {
        return mockOrderRepository.findAll();
    }

    public void clear() {
        mockOrderRepository.clear();
    }
}
