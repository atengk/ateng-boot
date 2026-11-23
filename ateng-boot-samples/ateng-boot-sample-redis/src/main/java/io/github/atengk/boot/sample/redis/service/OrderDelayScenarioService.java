package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.annotation.DelayedQueueListener;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.sample.redis.handler.SampleDelayedQueueFailureHandler;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * 订单延时队列场景服务
 * 演示 Redisson 双模延时队列：编程式投递、@DelayedQueueListener 声明式消费、毒丸重试耗尽与死信队列运维
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDelayScenarioService {

    public static final String QUEUE_ORDER_CANCEL = "sample:order:cancel";

    private final RedisDelayedQueue redisDelayedQueue;
    private final SampleDelayedQueueFailureHandler failureHandler;

    private final List<OrderDelayPayload> consumedOrders = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    /**
     * 编程式投递订单延时取消任务
     *
     * @param orderId 订单 ID
     * @param delay   延时时长
     */
    public void sendOrderCancelTask(String orderId, Duration delay) {
        sendOrderCancelTask(orderId, delay, "订单未支付超时自动取消");
    }

    /**
     * 编程式全参数投递订单延时取消任务
     *
     * @param orderId 订单 ID
     * @param delay   延时时长
     * @param reason  取消原因
     */
    public void sendOrderCancelTask(String orderId, Duration delay, String reason) {
        String effectiveReason = StringUtils.hasText(reason) ? reason : "订单未支付超时自动取消";
        OrderDelayPayload payload = new OrderDelayPayload(orderId, effectiveReason, System.currentTimeMillis());
        log.info("[OrderDelay] 编程式投递延时取消任务: queue={}, orderId={}, delay={}", QUEUE_ORDER_CANCEL, orderId, delay);
        redisDelayedQueue.offer(QUEUE_ORDER_CANCEL, payload, delay);
    }

    /**
     * 投递毒丸订单演练任务（触发 3 次退避重试耗尽并转入死信队列）
     *
     * @param orderId 毒丸订单编号（建议携带 POISON 标记）
     */
    public void sendPoisonOrder(String orderId) {
        sendOrderCancelTask(orderId, Duration.ZERO, "模拟毒丸重试耗尽演练任务");
    }

    /**
     * 声明式监听延时队列消费
     * 遇到毒丸订单时主动抛出业务异常，触发框架内部重试与死信流转
     *
     * @param payload 订单延时载荷
     */
    @DelayedQueueListener(queue = QUEUE_ORDER_CANCEL, concurrency = 1)
    public void onOrderCancel(OrderDelayPayload payload) {
        if (payload != null && payload.getOrderId() != null && payload.getOrderId().contains("POISON")) {
            log.error("[OrderDelay] 声明式监听捕获毒丸订单，抛出异常触发重试: orderId={}", payload.getOrderId());
            throw new BusinessException("毒丸订单触发模拟异常: orderId=" + payload.getOrderId());
        }
        log.info("[OrderDelay] 声明式监听到期消费: orderId={}, reason={}", payload.getOrderId(), payload.getReason());
        consumedOrders.add(payload);
        latch.countDown();
    }

    /**
     * 获取已消费订单列表
     */
    public List<OrderDelayPayload> getConsumedOrders() {
        return consumedOrders;
    }

    /**
     * 获取倒计时门闩
     */
    public CountDownLatch getLatch() {
        return latch;
    }

    /**
     * 重置状态（用于测试隔离）
     */
    public void reset(int expectedCount) {
        consumedOrders.clear();
        latch = new CountDownLatch(expectedCount);
    }

    /**
     * 查询取消订单队列的死信消息数量
     */
    public int getCancelOrderDeadLetterSize() {
        return redisDelayedQueue.getDeadLetterSize(QUEUE_ORDER_CANCEL);
    }

    /**
     * 拉取一条取消订单队列的死信消息
     */
    public DeadLetterMessage<OrderDelayPayload> pollCancelOrderDeadLetter() {
        return redisDelayedQueue.pollDeadLetter(QUEUE_ORDER_CANCEL, OrderDelayPayload.class);
    }

    /**
     * 批量非破坏性检视取消订单队列死信
     *
     * @param limit 检视上限
     * @return 死信消息列表
     */
    public List<DeadLetterMessage<OrderDelayPayload>> peekCancelOrderDeadLetters(int limit) {
        return redisDelayedQueue.peekDeadLetters(QUEUE_ORDER_CANCEL, limit, OrderDelayPayload.class);
    }

    /**
     * 受控重放单条取消订单死信消息
     *
     * @param delay 重放延时时长
     * @return 是否成功重放
     */
    public boolean replayCancelOrderDeadLetter(Duration delay) {
        return redisDelayedQueue.replayDeadLetter(QUEUE_ORDER_CANCEL, delay);
    }

    /**
     * 批量受控重放取消订单死信消息
     *
     * @param maxCount 最大重放条数
     * @param delay 重放延时时长
     * @return 实际成功重放数量
     */
    public int replayCancelOrderDeadLetters(int maxCount, Duration delay) {
        return redisDelayedQueue.replayDeadLetters(QUEUE_ORDER_CANCEL, maxCount, delay);
    }

    /**
     * 清空取消订单队列的死信消息
     */
    public void clearCancelOrderDeadLetter() {
        redisDelayedQueue.clearDeadLetterQueue(QUEUE_ORDER_CANCEL);
    }

    /**
     * 获取最近死信告警快照
     */
    public List<DeadLetterMessage<?>> getRecentAlerts() {
        return failureHandler.getRecentAlerts();
    }

    /**
     * 清空告警快照
     */
    public void clearAlerts() {
        failureHandler.clearAlerts();
    }
}
