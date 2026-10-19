package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.annotation.DelayedQueueListener;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单延时队列协同服务
 * 演示 Redisson 双模延时队列：编程式投递与 @DelayedQueueListener 声明式消费
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDelayQueueService {

    public static final String QUEUE_ORDER_CANCEL = "sample:order:cancel";

    private final RedisDelayedQueue redisDelayedQueue;
    private final List<OrderDelayPayload> consumedOrders = new CopyOnWriteArrayList<>();

    /**
     * 编程式投递订单延时取消任务
     *
     * @param orderId 订单 ID
     * @param delay   延时时长
     */
    public void sendOrderCancelTask(String orderId, Duration delay) {
        OrderDelayPayload payload = new OrderDelayPayload(orderId, "订单未支付超时自动取消", System.currentTimeMillis());
        log.info("编程式投递延时取消任务: queue={}, orderId={}, delay={}", QUEUE_ORDER_CANCEL, orderId, delay);
        redisDelayedQueue.offer(QUEUE_ORDER_CANCEL, payload, delay);
    }

    /**
     * 声明式监听延时队列消费
     *
     * @param payload 订单延时载荷
     */
    @DelayedQueueListener(queue = QUEUE_ORDER_CANCEL, concurrency = 1)
    public void onOrderCancel(OrderDelayPayload payload) {
        log.info("声明式监听到期消费: orderId={}, reason={}", payload.getOrderId(), payload.getReason());
        consumedOrders.add(payload);
    }

    /**
     * 获取已消费订单列表
     *
     * @return 消费记录列表
     */
    public List<OrderDelayPayload> getConsumedOrders() {
        return consumedOrders;
    }

    /**
     * 查询取消订单队列的死信消息数量
     *
     * @return 死信队列大小
     */
    public int getCancelOrderDeadLetterSize() {
        return redisDelayedQueue.getDeadLetterSize(QUEUE_ORDER_CANCEL);
    }

    /**
     * 拉取一条取消订单队列的死信消息
     *
     * @return 死信消息（无消息返回 null）
     */
    public DeadLetterMessage<OrderDelayPayload> pollCancelOrderDeadLetter() {
        return redisDelayedQueue.pollDeadLetter(QUEUE_ORDER_CANCEL, OrderDelayPayload.class);
    }
}
