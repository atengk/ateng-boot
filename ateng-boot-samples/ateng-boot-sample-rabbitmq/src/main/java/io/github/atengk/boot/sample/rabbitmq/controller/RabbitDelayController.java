package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rabbitmq.consumer.OrderDelayConsumer;
import io.github.atengk.boot.sample.rabbitmq.dto.OrderDelayRequest;
import io.github.atengk.boot.sample.rabbitmq.model.OrderTimeoutMessage;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 延迟消息与订单超时履约体验控制器
 * 演示通过插件延迟交换机投递延迟信令与超时自动履约关单
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/rabbitmq/delay")
@RequiredArgsConstructor
public class RabbitDelayController {

    private final TradeMessageProducer producer;
    private final ObjectProvider<OrderDelayConsumer> delayConsumerProvider;

    /**
     * 投递延迟订单超时关单消息
     *
     * @param request 延迟订单请求
     * @return 订单编号
     */
    @PostMapping("/timeout-order")
    public Result<String> sendDelayOrder(@Valid @RequestBody OrderDelayRequest request) {
        OrderTimeoutMessage message = new OrderTimeoutMessage(
                request.getOrderId(),
                request.getTimeoutSeconds(),
                request.getReason()
        );
        producer.sendOrderDelay(message, Duration.ofSeconds(request.getTimeoutSeconds()));
        return Result.success(request.getOrderId(), "延迟超时订单已投递，延迟时长: " + request.getTimeoutSeconds() + "秒");
    }

    /**
     * 探查已消费的超时关单消息列表
     *
     * @return 超时取消消息列表
     */
    @GetMapping("/received-orders")
    public Result<List<OrderTimeoutMessage>> getReceivedTimeoutOrders() {
        OrderDelayConsumer consumer = delayConsumerProvider.getIfAvailable();
        if (consumer == null) {
            return Result.success(Collections.emptyList(), "延迟消费者未启用（当前环境未开启 delayed-plugin-enabled）");
        }
        return Result.success(consumer.getTimeoutOrders());
    }

    /**
     * 清空延迟订单消费记录
     *
     * @return 操作结果
     */
    @DeleteMapping("/clear")
    public Result<Boolean> clearDelayRecords() {
        delayConsumerProvider.ifAvailable(OrderDelayConsumer::clear);
        return Result.success(true, "延迟消费记录已清空");
    }
}
