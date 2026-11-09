package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.consumer.OrderSingleRecordConsumer;
import io.github.atengk.boot.sample.kafka.dto.NativeTxProduceRequest;
import io.github.atengk.boot.sample.kafka.dto.OrderProduceRequest;
import io.github.atengk.boot.sample.kafka.dto.UserEventProduceRequest;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.service.TradeKafkaScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 消息可靠投递与事务感知体验控制器
 *
 * <p>涵盖同步发信、异步非阻塞投递、Murmur2 Key 分区保序、定向物理分区投递、
 * 本地 DB 事务感知提交发信（sendAfterCommit）、事务回滚零发信拦截以及 Kafka 原生分布式事务原子多写。</p>
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/kafka/produce")
@RequiredArgsConstructor
public class KafkaProduceController {

    private final TradeKafkaScenarioService tradeKafkaScenarioService;
    private final OrderSingleRecordConsumer orderSingleRecordConsumer;
    private final KafkaReliableTemplate kafkaReliableTemplate;

    /**
     * 同步可靠投递订单（支持 Murmur2 Key 分区哈希保序与定向物理分区投递）
     *
     * @param request 订单投递请求
     * @return 投递结果摘要
     */
    @PostMapping("/order-sync")
    public Result<String> sendOrderSync(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage message = request.toOrderMessage();
        SendResult<Object, Object> sendResult;
        if (request.getPartition() != null) {
            String key = request.getKey() != null ? request.getKey() : request.getOrderId();
            sendResult = tradeKafkaScenarioService.sendPartitionDirected(request.getPartition(), key, message);
        } else {
            sendResult = tradeKafkaScenarioService.sendSyncOrder(message);
        }
        String summary = String.format("TOPIC=%s, PARTITION=%d, OFFSET=%d",
                sendResult.getRecordMetadata().topic(),
                sendResult.getRecordMetadata().partition(),
                sendResult.getRecordMetadata().offset());
        return Result.success(summary, "同步投递订单成功");
    }

    /**
     * 异步非阻塞投递订单
     *
     * @param request 订单投递请求
     * @return 确认响应
     */
    @PostMapping("/order-async")
    public Result<String> sendOrderAsync(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage message = request.toOrderMessage();
        String key = request.getKey() != null ? request.getKey() : request.getOrderId();
        CompletableFuture<SendResult<Object, Object>> future =
                kafkaReliableTemplate.sendReliableAsync(KafkaScenarioTopologyConfig.TOPIC_ORDER, key, message);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[ASYNC-ORDER-FAILED] 订单异步投递失败: orderId={}", request.getOrderId(), ex);
            } else {
                log.info("[ASYNC-ORDER-SUCCESS] 订单异步投递确认: orderId={}, offset={}",
                        request.getOrderId(), result.getRecordMetadata().offset());
            }
        });
        return Result.success(request.getOrderId(), "订单异步非阻塞投递已提交发送队列");
    }

    /**
     * 异步非阻塞投递用户行为事件
     *
     * @param request 用户行为事件请求
     * @return 确认响应
     */
    @PostMapping("/user-event-async")
    public Result<String> sendUserEventAsync(@Valid @RequestBody UserEventProduceRequest request) {
        CompletableFuture<SendResult<Object, Object>> future =
                tradeKafkaScenarioService.sendAsyncUserEvent(request.toUserEventMessage());
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[ASYNC-EVENT-FAILED] 用户事件投递失败: userId={}", request.getUserId(), ex);
            } else {
                log.info("[ASYNC-EVENT-SUCCESS] 用户事件投递成功: userId={}, topic={}",
                        request.getUserId(), result.getRecordMetadata().topic());
            }
        });
        return Result.success(request.getUserId(), "用户事件异步投递已提交");
    }

    /**
     * 本地 DB 事务提交感知发信（事务提交物理发信）
     *
     * @param request 订单投递请求
     * @return 确认响应
     */
    @PostMapping("/order-tx-commit")
    public Result<String> createOrderWithCommit(@Valid @RequestBody OrderProduceRequest request) {
        tradeKafkaScenarioService.createOrderWithSendAfterCommit(request.toOrderMessage());
        return Result.success(request.getOrderId(), "本地事务成功提交，触发物理网络发信");
    }

    /**
     * 本地 DB 事务异常回滚发信（事务回滚零发信，杜绝幽灵消息）
     *
     * @param request 订单投递请求
     * @return 响应说明
     */
    @PostMapping("/order-tx-rollback")
    public Result<String> createOrderWithRollback(@Valid @RequestBody OrderProduceRequest request) {
        try {
            tradeKafkaScenarioService.createOrderWithRollback(request.toOrderMessage());
        } catch (IllegalStateException ex) {
            log.warn("[TX-ROLLBACK] 捕获预期本地事务异常: {}", ex.getMessage());
        }
        return Result.success(request.getOrderId(), "本地事务已回滚，发信钩子已自动作废（幽灵消息防御生效）");
    }

    /**
     * Kafka 原生分布式事务原子双写（订单与审计日志原子写入）
     *
     * @param request 原生事务双写请求
     * @return 事务执行结果
     */
    @PostMapping("/order-native-tx")
    public Result<String> executeNativeTx(@Valid @RequestBody NativeTxProduceRequest request) {
        String txResult = tradeKafkaScenarioService.executeTradeAtomicMultiSend(
                request.getOrder().toOrderMessage(), request.getAuditContent()
        );
        return Result.success(txResult, "Kafka 原生分布式事务双写成功");
    }

    /**
     * 探查单条消费者已成功接收的订单列表
     *
     * @return 订单消费记录列表
     */
    @GetMapping("/received-orders")
    public Result<List<OrderSingleRecordConsumer.ConsumedRecord>> getReceivedOrders() {
        return Result.success(orderSingleRecordConsumer.getConsumedRecords());
    }

    /**
     * 探查本地模拟数据库已落库的订单列表
     *
     * @return 本地落库订单列表
     */
    @GetMapping("/db-orders")
    public Result<List<String>> getLocalDbOrders() {
        return Result.success(tradeKafkaScenarioService.getLocalDbOrders());
    }

    /**
     * 清理消费与数据库内存测试桩状态
     *
     * @return 操作成功标记
     */
    @PostMapping("/clear")
    public Result<Boolean> clearState() {
        orderSingleRecordConsumer.clear();
        tradeKafkaScenarioService.clear();
        return Result.success(true, "生产场景测试桩现场清理完成");
    }
}
