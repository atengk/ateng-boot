package io.github.atengk.boot.redis.delay;

import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 企业级 Redis 延时队列全局静态操作门面工具类
 * 静态委托给受管单例 RedisDelayedQueue，提供无侵入快速投递、拉取、死信检视与受控重放能力
 *
 * @author Ateng
 * @since 2026-11-10
 */
public final class RedisDelayedQueueUtil {

    private static volatile RedisDelayedQueue staticQueue;

    private RedisDelayedQueueUtil() {
    }

    /**
     * 容器初始化绑定受管延时队列实例
     *
     * @param queue 受管延时队列门面
     */
    public static void init(RedisDelayedQueue queue) {
        Assert.notNull(queue, "RedisDelayedQueue must not be null");
        staticQueue = queue;
    }

    private static RedisDelayedQueue getQueue() {
        RedisDelayedQueue queue = staticQueue;
        Assert.state(queue != null, "RedisDelayedQueue尚未初始化，请确保已启用自动装配");
        return queue;
    }

    /**
     * 将消息加入延时队列（指定时间单位）
     */
    public static <T> void offer(String queueName, T message, long delay, TimeUnit timeUnit) {
        getQueue().offer(queueName, message, delay, timeUnit);
    }

    /**
     * 将消息加入延时队列（基于 Duration）
     */
    public static <T> void offer(String queueName, T message, Duration delay) {
        getQueue().offer(queueName, message, delay);
    }

    /**
     * 非阻塞尝试从就绪队列拉取一条消息
     */
    public static <T> T poll(String queueName) {
        return getQueue().poll(queueName);
    }

    /**
     * 非阻塞尝试从就绪队列拉取一条消息并自动转换为目标类型
     */
    public static <T> T poll(String queueName, Class<T> clazz) {
        return getQueue().poll(queueName, clazz);
    }

    /**
     * 阻塞拉取就绪队列消息
     */
    public static <T> T poll(String queueName, long timeout, TimeUnit timeUnit) throws InterruptedException {
        return getQueue().poll(queueName, timeout, timeUnit);
    }

    /**
     * 获取队列当前就绪积压消息数量
     */
    public static int size(String queueName) {
        return getQueue().size(queueName);
    }

    /**
     * 获取队列当前就绪积压消息数量（size 别名）
     */
    public static int getSize(String queueName) {
        return getQueue().getSize(queueName);
    }

    /**
     * 获取延时中未到期触发的元素个数
     */
    public static int delayedSize(String queueName) {
        return getQueue().delayedSize(queueName);
    }

    /**
     * 获取延时中未到期触发的元素个数（delayedSize 兼容别名）
     */
    public static int getDelayedSize(String queueName) {
        return getQueue().getDelayedSize(queueName);
    }

    /**
     * 将死信消息投递至死信队列
     */
    public static <T> void sendToDeadLetterQueue(String queueName, DeadLetterMessage<T> deadLetterMessage) {
        getQueue().sendToDeadLetterQueue(queueName, deadLetterMessage);
    }

    /**
     * 将死信消息投递至死信队列（简写别名）
     */
    public static <T> void sendToDeadLetter(String queueName, DeadLetterMessage<T> deadLetterMessage) {
        getQueue().sendToDeadLetter(queueName, deadLetterMessage);
    }

    /**
     * 查看死信队列队首消息（不移出队列）
     */
    public static <T> DeadLetterMessage<T> peekDeadLetter(String queueName) {
        return getQueue().peekDeadLetter(queueName);
    }

    /**
     * 查看死信队列队首消息并转换载荷为目标类型（不移出队列）
     */
    public static <T> DeadLetterMessage<T> peekDeadLetter(String queueName, Class<T> payloadClass) {
        return getQueue().peekDeadLetter(queueName, payloadClass);
    }

    /**
     * 批量非破坏性检视死信队列消息（不移出队列）
     */
    public static <T> List<DeadLetterMessage<T>> peekDeadLetters(String queueName, int limit) {
        return getQueue().peekDeadLetters(queueName, limit);
    }

    /**
     * 批量非破坏性检视死信队列消息并转换载荷为目标类型（不移出队列）
     */
    public static <T> List<DeadLetterMessage<T>> peekDeadLetters(String queueName, int limit, Class<T> payloadClass) {
        return getQueue().peekDeadLetters(queueName, limit, payloadClass);
    }

    /**
     * 从死信队列拉取一条死信消息
     */
    public static <T> DeadLetterMessage<T> pollDeadLetter(String queueName) {
        return getQueue().pollDeadLetter(queueName);
    }

    /**
     * 从死信队列拉取一条死信消息并转换载荷为目标类型
     */
    public static <T> DeadLetterMessage<T> pollDeadLetter(String queueName, Class<T> payloadClass) {
        return getQueue().pollDeadLetter(queueName, payloadClass);
    }

    /**
     * 获取死信队列积压消息数量
     */
    public static int deadLetterSize(String queueName) {
        return getQueue().deadLetterSize(queueName);
    }

    /**
     * 获取死信队列积压消息数量（兼容别名）
     */
    public static int getDeadLetterSize(String queueName) {
        return getQueue().getDeadLetterSize(queueName);
    }

    /**
     * 清空死信队列
     */
    public static void clearDeadLetterQueue(String queueName) {
        getQueue().clearDeadLetterQueue(queueName);
    }

    /**
     * 清空死信队列（简写别名）
     */
    public static void clearDeadLetter(String queueName) {
        getQueue().clearDeadLetter(queueName);
    }

    /**
     * 受控重放单条死信消息回原延时队列
     */
    public static boolean replayDeadLetter(String queueName, Duration delay) {
        return getQueue().replayDeadLetter(queueName, delay);
    }

    /**
     * 受控重放单条死信消息回原延时队列（立即到期投递）
     */
    public static boolean replayDeadLetter(String queueName) {
        return getQueue().replayDeadLetter(queueName);
    }

    /**
     * 批量受控重放死信消息回原延时队列
     */
    public static int replayDeadLetters(String queueName, int maxCount, Duration delay) {
        return getQueue().replayDeadLetters(queueName, maxCount, delay);
    }

    /**
     * 批量受控重放死信消息回原延时队列（立即到期投递）
     */
    public static int replayDeadLetters(String queueName, int maxCount) {
        return getQueue().replayDeadLetters(queueName, maxCount);
    }

    /**
     * 获取底层 RDelayedQueue 原生实例
     */
    public static <T> RDelayedQueue<T> getDelayedQueue(String queueName) {
        return getQueue().getDelayedQueue(queueName);
    }

    /**
     * 获取底层 RBlockingQueue 原生实例
     */
    public static <T> RBlockingQueue<T> getBlockingQueue(String queueName) {
        return getQueue().getBlockingQueue(queueName);
    }
}
