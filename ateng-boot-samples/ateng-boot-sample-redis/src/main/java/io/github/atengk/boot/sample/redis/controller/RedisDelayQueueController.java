package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import io.github.atengk.boot.sample.redis.model.delay.DeadLetterSummaryVO;
import io.github.atengk.boot.sample.redis.model.delay.OrderDelaySendRequest;
import io.github.atengk.boot.sample.redis.model.delay.OrderDelayVO;
import io.github.atengk.boot.sample.redis.service.OrderDelayScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Redisson 分布式双模延时队列控制器
 * 演示订单超时自动关单延时投递、已到期消费查询、毒丸重试耗尽演练、死信队列探针与失败 SPI 告警快照
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/delay-queue")
@RequiredArgsConstructor
public class RedisDelayQueueController {

    private final OrderDelayScenarioService orderDelayScenarioService;

    /**
     * 投递未支付订单超时取消延时任务
     *
     * @param request 延时任务请求参数
     * @return 延时任务投递结果 VO
     */
    @PostMapping("/order/cancel/send")
    public Result<OrderDelayVO> sendOrderCancel(@Valid @RequestBody OrderDelaySendRequest request) {
        log.info("[RedisDelayQueueController] 投递延时取消任务: orderId={}, delaySeconds={}",
                request.getOrderId(), request.getDelaySeconds());
        orderDelayScenarioService.sendOrderCancelTask(
                request.getOrderId(),
                Duration.ofSeconds(request.getDelaySeconds()),
                request.getReason()
        );

        OrderDelayVO vo = OrderDelayVO.builder()
                .orderId(request.getOrderId())
                .status("SCHEDULED")
                .message("延时取消任务投递成功，将在 " + request.getDelaySeconds() + " 秒后到期触发")
                .triggerTime(LocalDateTime.now().plusSeconds(request.getDelaySeconds()))
                .build();
        return Result.success(vo);
    }

    /**
     * 查询已被监听器消费的订单取消记录列表
     *
     * @return 已消费订单取消载荷列表
     */
    @GetMapping("/order/cancel/consumed")
    public Result<List<OrderDelayPayload>> getConsumedOrders() {
        return Result.success(orderDelayScenarioService.getConsumedOrders());
    }

    /**
     * 投递毒丸订单演练任务
     * 毒丸消息将触发声明式监听抛出业务异常，经 3 次真实退避重试耗尽后自动封送死信队列并触发 SPI 告警
     *
     * @param orderId 毒丸订单 ID（可选，默认自动生成）
     * @return 演练执行提示
     */
    @PostMapping("/order/cancel/send-poison")
    public Result<String> sendPoisonOrder(@RequestParam(value = "orderId", required = false) String orderId) {
        String effectiveOrderId = StringUtils.hasText(orderId) ? orderId : "POISON-ORD-" + IdUtil.fastSimpleUUID();
        log.warn("[RedisDelayQueueController] 投递毒丸订单演练任务: orderId={}", effectiveOrderId);
        orderDelayScenarioService.sendPoisonOrder(effectiveOrderId);
        return Result.success("毒丸演练订单投递成功: orderId=" + effectiveOrderId + "，请观察重试日志与死信流转");
    }

    /**
     * 查询订单取消延时队列的死信消息积压数量
     *
     * @return 死信积压统计 VO
     */
    @GetMapping("/dead-letter/count")
    public Result<DeadLetterSummaryVO> getDeadLetterCount() {
        int count = orderDelayScenarioService.getCancelOrderDeadLetterSize();
        DeadLetterSummaryVO vo = DeadLetterSummaryVO.builder()
                .queueName(OrderDelayScenarioService.QUEUE_ORDER_CANCEL)
                .deadLetterQueueName(OrderDelayScenarioService.QUEUE_ORDER_CANCEL + ":dlq")
                .deadLetterCount(count)
                .build();
        return Result.success(vo);
    }

    /**
     * 从死信队列拉取一条消息（运维诊断拉取）
     *
     * @return 死信消息结构体
     */
    @GetMapping("/dead-letter/poll")
    public Result<DeadLetterMessage<OrderDelayPayload>> pollDeadLetter() {
        DeadLetterMessage<OrderDelayPayload> deadLetter = orderDelayScenarioService.pollCancelOrderDeadLetter();
        return Result.success(deadLetter);
    }

    /**
     * 清空订单取消队列的死信消息
     *
     * @return 清空结果
     */
    @DeleteMapping("/dead-letter/clear")
    public Result<Boolean> clearDeadLetter() {
        log.info("[RedisDelayQueueController] 清空延时队列死信");
        orderDelayScenarioService.clearCancelOrderDeadLetter();
        return Result.success(true);
    }

    /**
     * 查询 DelayedQueueFailureHandler SPI 捕获的最近失败告警快照列表
     *
     * @return 告警快照列表
     */
    @GetMapping("/alerts")
    public Result<List<DeadLetterMessage<?>>> getAlerts() {
        return Result.success(orderDelayScenarioService.getRecentAlerts());
    }
}
