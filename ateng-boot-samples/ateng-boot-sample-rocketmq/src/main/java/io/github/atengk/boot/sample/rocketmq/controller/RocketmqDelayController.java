package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderDelayConsumer;
import io.github.atengk.boot.sample.rocketmq.dto.OrderDelayRequest;
import io.github.atengk.boot.sample.rocketmq.dto.OrderDelayResponse;
import io.github.atengk.boot.sample.rocketmq.dto.OrderRecallRequest;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import io.github.atengk.boot.sample.rocketmq.service.BatchDelayScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RocketMQ 延时调度与精准撤回场景控制器
 *
 * <p>提供相对延时发信、绝对时间戳定时发信、延时关单精准撤回与延时消费审计端点。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/delay")
@RequiredArgsConstructor
public class RocketmqDelayController {

    private static final Duration ASYNC_AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private final BatchDelayScenarioService batchDelayScenarioService;
    private final OrderDelayConsumer delayConsumer;

    /**
     * 相对时长延时发送订单消息（返回 recallHandle 支持撤回，支持自定义属性透传）
     *
     * @param request 延时请求参数
     * @return 延时响应（包含撤回句柄）
     */
    @PostMapping("/duration")
    public Result<OrderDelayResponse> sendDelayed(@Valid @RequestBody OrderDelayRequest request) {
        OrderMessage order = request.toOrderMessage();
        long seconds = request.resolveDelaySeconds();
        Duration delay = Duration.ofSeconds(seconds);
        long deliverAtTimestamp = System.currentTimeMillis() + delay.toMillis();
        SendReceipt receipt = batchDelayScenarioService.sendDelayed(order, delay, request.getProperties());
        return Result.success(buildDelayResponse(order, receipt, seconds, deliverAtTimestamp, "DELAY_SCHEDULED"));
    }

    /**
     * 异步相对时长延时发送订单消息（支持自定义属性透传）
     *
     * @param request 延时请求参数
     * @return 延时响应（包含撤回句柄）
     */
    @PostMapping("/duration-async")
    public Result<OrderDelayResponse> sendDelayedAsync(@Valid @RequestBody OrderDelayRequest request) {
        OrderMessage order = request.toOrderMessage();
        long seconds = request.resolveDelaySeconds();
        Duration delay = Duration.ofSeconds(seconds);
        long deliverAtTimestamp = System.currentTimeMillis() + delay.toMillis();
        SendReceipt receipt = awaitFuture(batchDelayScenarioService.sendDelayedAsync(order, delay, request.getProperties()));
        return Result.success(buildDelayResponse(order, receipt, seconds, deliverAtTimestamp, "DELAY_ASYNC_SCHEDULED"));
    }

    /**
     * 绝对时间戳定时调度订单消息（返回 recallHandle，支持自定义属性透传）
     *
     * @param request 定时请求参数
     * @return 定时响应（包含撤回句柄）
     */
    @PostMapping("/deliver-at")
    public Result<OrderDelayResponse> sendDeliverAt(@Valid @RequestBody OrderDelayRequest request) {
        OrderMessage order = request.toOrderMessage();
        long targetTimestamp = request.resolveDeliverAtTimestamp();
        Instant deliverAt = Instant.ofEpochMilli(targetTimestamp);
        SendReceipt receipt = batchDelayScenarioService.sendDeliverAt(order, deliverAt, request.getProperties());
        return Result.success(buildDelayResponse(order, receipt, null, targetTimestamp, "SCHEDULED_SUCCESS"));
    }

    /**
     * 异步绝对时间戳定时调度订单消息（支持自定义属性透传）
     *
     * @param request 定时请求参数
     * @return 定时响应（包含撤回句柄）
     */
    @PostMapping("/deliver-at-async")
    public Result<OrderDelayResponse> sendDeliverAtAsync(@Valid @RequestBody OrderDelayRequest request) {
        OrderMessage order = request.toOrderMessage();
        long targetTimestamp = request.resolveDeliverAtTimestamp();
        Instant deliverAt = Instant.ofEpochMilli(targetTimestamp);
        SendReceipt receipt = awaitFuture(batchDelayScenarioService.sendDeliverAtAsync(order, deliverAt, request.getProperties()));
        return Result.success(buildDelayResponse(order, receipt, null, targetTimestamp, "SCHEDULED_ASYNC_SUCCESS"));
    }

    private <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return future.get(ASYNC_AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 RocketMQ 异步延时发送结果被中断", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("等待 RocketMQ 异步延时发送结果超时", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("异步发送 RocketMQ 延时消息失败", cause != null ? cause : ex);
        }
    }

    private OrderDelayResponse buildDelayResponse(
            OrderMessage order,
            SendReceipt receipt,
            Long delaySeconds,
            long deliverAtTimestamp,
            String status) {
        return OrderDelayResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .delaySeconds(delaySeconds)
                .deliverAtTimestamp(deliverAtTimestamp)
                .recallHandle(receipt.getRecallHandle())
                .status(status)
                .build();
    }

    /**
     * 精准撤回未到期的定时/延时消息
     *
     * @param request 撤回请求参数
     * @return 成功响应
     */
    @PostMapping("/recall")
    public Result<Boolean> recallDelayedMessage(@Valid @RequestBody OrderRecallRequest request) {
        String topic = StringUtils.hasText(request.getTopic()) ? request.getTopic() : TradeRocketmqProducer.TOPIC_DELAY;
        batchDelayScenarioService.recallDelayedMessage(topic, request.getRecallHandle());
        return Result.success(true);
    }

    /**
     * 查询延时队列已消费的订单列表
     *
     * @return 已消费订单列表
     */
    @GetMapping("/received")
    public Result<List<OrderMessage>> getReceivedDelayOrders() {
        return Result.success(delayConsumer.getReceivedDelayOrders());
    }

    /**
     * 清空延时消费审计记录
     *
     * @return 空响应
     */
    @DeleteMapping("/clear")
    public Result<Void> clearReceivedDelayOrders() {
        delayConsumer.clear();
        return Result.success();
    }
}
