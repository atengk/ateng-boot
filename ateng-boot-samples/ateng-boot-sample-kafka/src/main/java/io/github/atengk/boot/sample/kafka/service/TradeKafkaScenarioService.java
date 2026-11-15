package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.repository.MockOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        return sendSyncOrder(order, null);
    }

    /**
     * 同步按 Key 发送订单并携带自定义业务消息头
     */
    public SendResult<Object, Object> sendSyncOrder(OrderMessage order, Map<String, String> customHeaders) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order, customHeaders);
    }

    /**
     * 异步按 Key 发送订单消息，返回 CompletableFuture 结果
     *
     * @param key   业务 Key
     * @param order 订单消息
     * @return 异步发送 Future
     */
    public CompletableFuture<SendResult<Object, Object>> sendAsyncOrder(String key, OrderMessage order) {
        return sendAsyncOrder(key, order, null);
    }

    /**
     * 异步按 Key 发送订单消息并携带自定义业务消息头
     */
    public CompletableFuture<SendResult<Object, Object>> sendAsyncOrder(String key, OrderMessage order, Map<String, String> customHeaders) {
        return kafkaReliableTemplate.sendReliableAsync(KafkaScenarioTopologyConfig.TOPIC_ORDER, key, order, customHeaders);
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
        return sendPartitionDirected(partition, key, payload, null);
    }

    /**
     * 定向发送消息至指定物理分区并携带自定义业务消息头
     */
    public SendResult<Object, Object> sendPartitionDirected(int partition, String key, Object payload, Map<String, String> customHeaders) {
        return kafkaReliableTemplate.sendReliable(
                KafkaScenarioTopologyConfig.TOPIC_ORDER, partition, key, payload, null, null, customHeaders);
    }

    /**
     * 批量可靠发送订单消息
     */
    public List<SendResult<Object, Object>> sendBatchOrders(List<OrderMessage> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        String previousTenant = TenantContextHolder.getTenantId();
        List<CompletableFuture<SendResult<Object, Object>>> futures = new ArrayList<>(orders.size());
        try {
            for (OrderMessage order : orders) {
                if (order == null) {
                    continue;
                }
                if (order.getTenantId() != null && !order.getTenantId().isBlank()) {
                    TenantContextHolder.setTenantId(order.getTenantId());
                } else {
                    TenantContextHolder.clear();
                }
                futures.add(kafkaReliableTemplate.sendReliableAsync(
                        KafkaScenarioTopologyConfig.TOPIC_BATCH,
                        order.getOrderId(),
                        order));
            }
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantId(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 异步批量可靠发送订单消息（使用 KafkaReliableTemplate.sendBatchRecordsAsync 两阶段前置防卫，保留重复 orderId 多条消息）
     *
     * @param orders 订单消息列表
     * @return 聚合发送结果 Future
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchOrdersAsync(List<OrderMessage> orders) {
        return kafkaReliableTemplate.sendBatchRecordsAsync(toBatchRecords(orders));
    }

    /**
     * 本地事务提交后批量发射订单消息（事务前完成阶段 1 全量校验与四维上下文捕获，提交后批量下发）
     *
     * @param orders 订单消息列表
     */
    @Transactional
    public void createBatchOrdersWithSendAfterCommit(List<OrderMessage> orders) {
        List<ProducerRecord<Object, Object>> records = toBatchRecords(orders);
        if (records.isEmpty()) {
            return;
        }
        // 先注册事务感知批量发信，确保阶段 1 Fail-Fast 违规时在写入内存 MockOrderRepository 前中止
        kafkaReliableTemplate.sendBatchRecordsAfterCommit(records);
        for (OrderMessage order : orders) {
            mockOrderRepository.save(order.getOrderId());
        }
    }

    private List<ProducerRecord<Object, Object>> toBatchRecords(List<OrderMessage> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProducerRecord<Object, Object>> records = new ArrayList<>(orders.size());
        for (int i = 0; i < orders.size(); i++) {
            OrderMessage order = orders.get(i);
            Assert.notNull(order, "orders[" + i + "] 订单消息不能为空");
            records.add(new ProducerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, order.getOrderId(), order));
        }
        return records;
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
        createOrderWithSendAfterCommit(order, null);
    }

    /**
     * 本地事务成功提交后发射消息并携带自定义业务消息头 (sendAfterCommit)
     */
    @Transactional
    public void createOrderWithSendAfterCommit(OrderMessage order, Map<String, String> customHeaders) {
        log.info("[DB-SAVE] 订单数据落库成功: orderId={}", order.getOrderId());
        mockOrderRepository.save(order.getOrderId());
        kafkaReliableTemplate.sendAfterCommit(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order, customHeaders);
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

    /**
     * 原生 Kafka 事务发送大体积载荷（用于验证事务代理层 Fail-Fast 限流防线）
     */
    public String executeNativeTxLargePayload(byte[] payload) {
        return kafkaReliableTemplate.executeInTransaction(operations -> {
            operations.send(KafkaScenarioTopologyConfig.TOPIC_ORDER, "large-tx-key", payload);
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
