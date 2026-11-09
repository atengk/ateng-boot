package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rabbitmq.consumer.BatchOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.PriorityOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.dto.BatchOrderRequest;
import io.github.atengk.boot.sample.rabbitmq.dto.PriorityOrderRequest;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 生产端微批投递与 Quorum 优先级消费体验控制器
 * 演示 sendBatchReliable 批量发送、batchRabbitListener 批量削峰与优先级消息分级路由
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/rabbitmq")
@RequiredArgsConstructor
public class RabbitBatchAndPriorityController {

    private final TradeMessageProducer producer;
    private final BatchOrderConsumer batchOrderConsumer;
    private final PriorityOrderConsumer priorityOrderConsumer;

    /**
     * 批量同步可靠投递订单消息 (聚合等待所有 Broker Confirm ACK)
     *
     * @param request 批量订单请求
     * @return 实际发送数量
     */
    @PostMapping("/batch/orders")
    public Result<Integer> sendBatchOrdersSync(@Valid @RequestBody BatchOrderRequest request) {
        List<OrderCreateMessage> orders = request.getOrders().stream()
                .map(req -> new OrderCreateMessage(req.getOrderId(), req.getAmount(), req.getUserId(), LocalDateTime.now()))
                .toList();
        producer.sendBatchOrders(orders);
        return Result.success(orders.size(), "批量订单同步投递完成并全部收到 Broker ACK");
    }

    /**
     * 批量异步可靠投递订单消息
     *
     * @param request 批量订单请求
     * @return 实际发送数量
     */
    @PostMapping("/batch/orders-async")
    public Result<Integer> sendBatchOrdersAsync(@Valid @RequestBody BatchOrderRequest request) {
        List<OrderCreateMessage> orders = request.getOrders().stream()
                .map(req -> new OrderCreateMessage(req.getOrderId(), req.getAmount(), req.getUserId(), LocalDateTime.now()))
                .toList();
        CompletableFuture<Void> future = producer.sendBatchOrdersAsync(orders);
        future.thenRun(() -> log.info("[ASYNC-BATCH-ACK] 批量订单异步投递全部确认成功: count={}", orders.size()));
        return Result.success(orders.size(), "批量订单异步投递已发射，后台聚合等待 ACK");
    }

    /**
     * 探查批量削峰消费者接收到的所有订单记录
     *
     * @return 订单列表
     */
    @GetMapping("/batch/received-batches")
    public Result<List<OrderCreateMessage>> getReceivedBatchOrders() {
        return Result.success(batchOrderConsumer.getAllReceivedOrders());
    }

    /**
     * 投递带有优先级的业务订单 (Quorum 仲裁队列优先级)
     *
     * @param request 优先级订单请求
     * @return 订单编号
     */
    @PostMapping("/priority/order")
    public Result<String> sendPriorityOrder(@Valid @RequestBody PriorityOrderRequest request) {
        OrderCreateMessage message = new OrderCreateMessage(
                request.getOrder().getOrderId(),
                request.getOrder().getAmount(),
                request.getOrder().getUserId(),
                LocalDateTime.now()
        );
        producer.sendPriorityOrder(message, request.getPriority());
        return Result.success(request.getOrder().getOrderId(), "优先级订单投递成功: priority=" + request.getPriority());
    }

    /**
     * 探查优先级消费者接收到的订单列表
     *
     * @return 订单列表
     */
    @GetMapping("/priority/received-orders")
    public Result<List<OrderCreateMessage>> getPriorityOrders() {
        return Result.success(priorityOrderConsumer.getReceivedOrders());
    }

    /**
     * 探查优先级消费者接收到的优先级属性列表
     *
     * @return 优先级数值列表
     */
    @GetMapping("/priority/received-priorities")
    public Result<List<Integer>> getPriorityLevels() {
        return Result.success(priorityOrderConsumer.getReceivedPriorities());
    }

    /**
     * 清空批量与优先级消费记录
     *
     * @return 操作结果
     */
    @DeleteMapping("/batch/clear")
    public Result<Boolean> clearBatchAndPriority() {
        batchOrderConsumer.clear();
        priorityOrderConsumer.clear();
        return Result.success(true, "批量与优先级消费记录已清空");
    }
}
