package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderFifoConsumer;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderSqlConsumer;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderStandardConsumer;
import io.github.atengk.boot.sample.rocketmq.dto.BatchProduceResponse;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceRequest;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceResponse;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.service.StandardRocketmqScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RocketMQ 基础与保序消息发送场景控制器
 *
 * <p>提供同步发送、异步发送、单向发送、FIFO 顺序发送、SQL92 属性过滤发送、批量发送与消费审计端点。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/produce")
@RequiredArgsConstructor
public class RocketmqProduceController {

    private static final Duration ASYNC_AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private final StandardRocketmqScenarioService standardScenarioService;
    private final OrderStandardConsumer standardConsumer;
    private final OrderFifoConsumer fifoConsumer;
    private final OrderSqlConsumer sqlConsumer;

    /**
     * 同步阻塞发送标准订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/sync")
    public Result<OrderProduceResponse> sendSync(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        String tag = StringUtils.hasText(request.getTag()) ? request.getTag() : "create";
        SendReceipt receipt = standardScenarioService.sendSync(order, tag, request.getProperties());
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag(tag)
                .status("SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 异步非阻塞发送标准订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/async")
    public Result<OrderProduceResponse> sendAsync(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        CompletableFuture<SendReceipt> future = standardScenarioService.sendAsync(order, request.getProperties());
        SendReceipt receipt = awaitFuture(future);
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("create")
                .status("ASYNC_SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 单向极速发送订单埋点消息
     *
     * @param request 发送请求参数
     * @return 空响应
     */
    @PostMapping("/oneway")
    public Result<Void> sendOneway(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        standardScenarioService.sendOneway(order);
        return Result.success();
    }

    /**
     * 局部保序发送 FIFO 顺序订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/fifo")
    public Result<OrderProduceResponse> sendFifo(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        String messageGroup = StringUtils.hasText(request.getMessageGroup()) ? request.getMessageGroup() : order.getOrderId();
        SendReceipt receipt = standardScenarioService.sendFifo(order, messageGroup);
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("fifo")
                .status("FIFO_SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 发送携带自定义属性支持 SQL92 服务端过滤的订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/sql")
    public Result<OrderProduceResponse> sendSqlFiltered(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        SendReceipt receipt = standardScenarioService.sendSqlFiltered(order, order.getAmount());
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("sql")
                .status("SQL_SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 批量可靠发送订单消息（支持自动分片并发投递与自定义属性透传，接入 ValidList 工业级校验）
     *
     * @param requests 批量发送请求列表
     * @return 批量发送统一响应
     */
    @PostMapping("/batch")
    public Result<BatchProduceResponse> sendBatch(@Valid @RequestBody ValidList<OrderProduceRequest> requests) {
        List<OrderMessage> orders = requests.stream().map(OrderProduceRequest::toOrderMessage).toList();
        List<SendReceipt> receipts = standardScenarioService.sendBatch(orders, "batch", extractBatchProperties(requests));
        return Result.success(buildBatchResponse(requests, orders, receipts));
    }

    /**
     * 异步批量可靠发送订单消息（支持自动分片并发投递与自定义属性透传）
     *
     * @param requests 批量发送请求列表
     * @return 批量发送统一响应
     */
    @PostMapping("/batch-async")
    public Result<BatchProduceResponse> sendBatchAsync(@Valid @RequestBody ValidList<OrderProduceRequest> requests) {
        List<OrderMessage> orders = requests.stream().map(OrderProduceRequest::toOrderMessage).toList();
        List<SendReceipt> receipts = awaitFuture(standardScenarioService
                .sendBatchAsync(orders, "batch", extractBatchProperties(requests)));
        return Result.success(buildBatchResponse(requests, orders, receipts));
    }

    private <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return future.get(ASYNC_AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 RocketMQ 异步发送结果被中断", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("等待 RocketMQ 异步发送结果超时", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("异步发送 RocketMQ 消息失败", cause != null ? cause : ex);
        }
    }

    private BatchProduceResponse buildBatchResponse(
            ValidList<OrderProduceRequest> requests,
            List<OrderMessage> orders,
            List<SendReceipt> receipts) {
        List<String> orderIds = orders.stream().map(OrderMessage::getOrderId).toList();
        List<String> messageIds = receipts.stream().map(r -> r.getMessageId().toString()).toList();
        return BatchProduceResponse.builder()
                .total(requests.size())
                .successCount(receipts.size())
                .orderIds(orderIds)
                .messageIds(messageIds)
                .build();
    }

    private Map<String, String> extractBatchProperties(List<OrderProduceRequest> requests) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (OrderProduceRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("批量请求列表中不能包含 null 元素");
            }
            Map<String, String> props = request.getProperties();
            if (props == null || props.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : props.entrySet()) {
                String existing = merged.putIfAbsent(entry.getKey(), entry.getValue());
                if (existing != null && !Objects.equals(existing, entry.getValue())) {
                    throw new IllegalArgumentException(
                            "批量消息属性键 [" + entry.getKey() + "] 存在冲突取值，请保持批次内属性一致");
                }
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    /**
     * 查询当前所有基础投递场景（标准、FIFO、SQL92）已消费的订单列表
     *
     * @return 消费记录列表
     */
    @GetMapping("/received")
    public Result<List<OrderMessage>> getReceivedOrders() {
        List<OrderMessage> all = new ArrayList<>();
        all.addAll(standardConsumer.getReceivedOrders());
        all.addAll(fifoConsumer.getReceivedFifoOrders());
        all.addAll(sqlConsumer.getReceivedSqlOrders());
        return Result.success(all);
    }

    /**
     * 清空所有基础场景的消费审计记录
     *
     * @return 空响应
     */
    @DeleteMapping("/clear")
    public Result<Void> clearReceivedOrders() {
        standardConsumer.clear();
        fifoConsumer.clear();
        sqlConsumer.clear();
        return Result.success();
    }
}
