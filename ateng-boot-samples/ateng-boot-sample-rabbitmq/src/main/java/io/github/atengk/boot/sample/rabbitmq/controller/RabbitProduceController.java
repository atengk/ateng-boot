package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rabbitmq.consumer.LogTopicConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.OrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.dto.LogSendRequest;
import io.github.atengk.boot.sample.rabbitmq.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 生产端可靠投递与路由体验控制器
 * 提供同步等待 Confirm ACK、异步发信、Topic 层次通配与不可路由退回体验端点
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/rabbitmq/produce")
@RequiredArgsConstructor
public class RabbitProduceController {

    private final TradeMessageProducer producer;
    private final OrderConsumer orderConsumer;
    private final LogTopicConsumer logTopicConsumer;

    /**
     * 同步可靠投递订单创建消息 (阻塞等待 Broker Confirm ACK)
     *
     * @param request 订单请求
     * @return 订单ID
     */
    @PostMapping("/order")
    public Result<String> sendOrderSync(@Valid @RequestBody OrderCreateRequest request) {
        OrderCreateMessage message = new OrderCreateMessage(
                request.getOrderId(),
                request.getAmount(),
                request.getUserId(),
                LocalDateTime.now()
        );
        producer.sendOrderCreate(message);
        return Result.success(request.getOrderId(), "订单同步可靠投递成功并收到 Broker Confirm ACK");
    }

    /**
     * 异步非阻塞可靠投递订单创建消息
     *
     * @param request 订单请求
     * @return 异步投递成功凭据
     */
    @PostMapping("/order-async")
    public Result<String> sendOrderAsync(@Valid @RequestBody OrderCreateRequest request) {
        OrderCreateMessage message = new OrderCreateMessage(
                request.getOrderId(),
                request.getAmount(),
                request.getUserId(),
                LocalDateTime.now()
        );
        CompletableFuture<CorrelationData.Confirm> future = producer.sendOrderCreateAsync(message);
        future.thenAccept(confirm -> log.info("[ASYNC-ACK] 收到异步确认: orderId={}, ack={}",
                request.getOrderId(), confirm.isAck()));
        return Result.success(request.getOrderId(), "订单异步非阻塞投递成功，后台等待 Confirm");
    }

    /**
     * Topic 通配路由投递错误日志
     *
     * @param request 日志请求
     * @return 响应消息
     */
    @PostMapping("/log")
    public Result<String> sendTopicLog(@Valid @RequestBody LogSendRequest request) {
        LogMessage message = new LogMessage(request.getLevel(), request.getServiceName(), request.getContent());
        producer.sendLogError(request.getRoutingKey(), message);
        return Result.success(request.getRoutingKey(), "Topic 层次通配日志投递成功");
    }

    /**
     * 模拟投递不可路由消息 (触发 ReturnsCallback 告警与 SPI 回调)
     *
     * @param payload 测试载荷
     * @return 响应说明
     */
    @PostMapping("/unroutable")
    public Result<String> sendUnroutable(@RequestParam(defaultValue = "unroutable-test-data") String payload) {
        producer.sendBizMessage("unroutable-key-none", payload);
        return Result.success(payload, "不可路由消息投递完成，触发 ReturnsCallback 退回告警");
    }

    /**
     * 探查已消费的订单列表
     *
     * @return 订单创建消息列表
     */
    @GetMapping("/received-orders")
    public Result<List<OrderCreateMessage>> getReceivedOrders() {
        return Result.success(orderConsumer.getReceivedMessages());
    }

    /**
     * 探查已消费的系统日志列表
     *
     * @return 日志消息列表
     */
    @GetMapping("/received-logs")
    public Result<List<LogMessage>> getReceivedLogs() {
        return Result.success(logTopicConsumer.getReceivedLogs());
    }

    /**
     * 清空探查记录
     *
     * @return 操作结果
     */
    @DeleteMapping("/clear")
    public Result<Boolean> clearRecords() {
        orderConsumer.clear();
        logTopicConsumer.clear();
        return Result.success(true, "消费记录已清空");
    }
}
