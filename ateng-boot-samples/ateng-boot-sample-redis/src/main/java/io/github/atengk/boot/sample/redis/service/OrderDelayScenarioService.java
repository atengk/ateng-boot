package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.annotation.DelayedQueueListener;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * 订单延时队列场景服务
 * 演示 Redisson 双模延时队列：编程式投递、@DelayedQueueListener 声明式消费与死信队列流转
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
    private final List<OrderDelayPayload> consumedOrders = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    /**
     * 编程式投递订单延时取消任务
     *
     * @param orderId 订单 ID
     * @param delay   延时时长
     */
    public void sendOrderCancelTask(String orderId, Duration delay) {
        OrderDelayPayload payload = new OrderDelayPayload(orderId, "订单未支付超时自动取消", System.currentTimeMillis());
        log.info("[OrderDelay] 编程式投递延时取消任务: queue={}, orderId={}, delay={}", QUEUE_ORDER_CANCEL, orderId, delay);
        redisDelayedQueue.offer(QUEUE_ORDER_CANCEL, payload, delay);
    }

    /**
     * 声明式监听延时队列消费
     *
     * @param payload 订单延时载荷
     */
    @DelayedQueueListener(queue = QUEUE_ORDER_CANCEL, concurrency = 1)
    public void onOrderCancel(OrderDelayPayload payload) {
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
     * 手动将异常订单流转至死信队列（模拟消费重试耗尽场景）
     *
     * @param orderId      订单 ID
     * @param retryCount   重试次数
     * @param errorMessage 失败原因
     */
    public void sendOrderToDeadLetter(String orderId, int retryCount, String errorMessage) {
        OrderDelayPayload payload = new OrderDelayPayload(orderId, "模拟重试耗尽死信订单", System.currentTimeMillis());
        DeadLetterMessage<OrderDelayPayload> deadLetter = new DeadLetterMessage<>(
                QUEUE_ORDER_CANCEL,
                payload,
                retryCount,
                errorMessage,
                "BusinessRetryExhaustedException",
                LocalDateTime.now()
        );
        redisDelayedQueue.sendToDeadLetter(QUEUE_ORDER_CANCEL, deadLetter);
        log.info("[OrderDelay] 订单重试耗尽转入死信队列: orderId={}, retryCount={}, error={}", orderId, retryCount, errorMessage);
    }
}
