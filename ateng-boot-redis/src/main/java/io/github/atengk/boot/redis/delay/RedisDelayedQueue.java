package io.github.atengk.boot.redis.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 企业级 Redis 轻量延时任务队列编程式门面
 * 基于 Redisson 原生 RDelayedQueue 与 RBlockingQueue 封装，提供直观的入队出队与生命周期管理能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisDelayedQueue implements DisposableBean {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final RedisProperties redisProperties;
    private final Map<String, RDelayedQueue<Object>> delayedQueueMap = new ConcurrentHashMap<>();

    public RedisDelayedQueue(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this(redissonClient, objectMapper, null);
    }

    public RedisDelayedQueue(RedissonClient redissonClient, ObjectMapper objectMapper, RedisProperties redisProperties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : RedisSerializerFactory.createSafeObjectMapper();
        this.redisProperties = redisProperties;
    }


    /**
     * 将消息加入延时队列
     *
     * @param queueName 队列名称
     * @param message 消息体
     * @param delay 延时时长
     * @param timeUnit 时间单位
     * @param <T> 消息泛型
     */
    public <T> void offer(String queueName, T message, long delay, TimeUnit timeUnit) {
        Assert.hasText(queueName, "queueName must not be blank");
        Assert.notNull(message, "message must not be null");
        Assert.isTrue(delay >= 0, "delay must be greater than or equal to 0");
        Assert.notNull(timeUnit, "timeUnit must not be null");

        RDelayedQueue<T> delayedQueue = getDelayedQueue(queueName);
        delayedQueue.offer(message, delay, timeUnit);
    }

    /**
     * 将消息加入延时队列（基于 Duration）
     *
     * @param queueName 队列名称
     * @param message 消息体
     * @param delay 延时时长对象
     * @param <T> 消息泛型
     */
    public <T> void offer(String queueName, T message, Duration delay) {
        Assert.notNull(delay, "delay must not be null");
        offer(queueName, message, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 非阻塞尝试从就绪队列拉取一条消息（若无到期消息立即返回 null）
     *
     * @param queueName 队列名称
     * @param <T> 消息泛型
     * @return 到期消息，无到期消息时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T poll(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        RBlockingQueue<Object> blockingQueue = getBlockingQueue(queueName);
        return (T) blockingQueue.poll();
    }

    /**
     * 非阻塞尝试从就绪队列拉取一条消息并自动反序列化为目标类型
     *
     * @param queueName 队列名称
     * @param clazz 目标类型 Class
     * @param <T> 目标类型泛型
     * @return 目标类型消息对象，无消息时返回 null
     */
    public <T> T poll(String queueName, Class<T> clazz) {
        Object raw = poll(queueName);
        return convertValue(raw, clazz);
    }

    /**
     * 阻塞拉取就绪队列消息（指定最长等待超时时长）
     *
     * @param queueName 队列名称
     * @param timeout 超时时间
     * @param timeUnit 时间单位
     * @param <T> 消息泛型
     * @return 到期消息，超时返回 null
     * @throws InterruptedException 线程中断异常
     */
    @SuppressWarnings("unchecked")
    public <T> T poll(String queueName, long timeout, TimeUnit timeUnit) throws InterruptedException {
        Assert.hasText(queueName, "queueName must not be blank");
        Assert.notNull(timeUnit, "timeUnit must not be null");
        RBlockingQueue<Object> blockingQueue = getBlockingQueue(queueName);
        return (T) blockingQueue.poll(timeout, timeUnit);
    }

    /**
     * 阻塞拉取就绪队列消息并自动转换为目标类型（指定最长等待超时时长）
     *
     * @param queueName 队列名称
     * @param timeout 超时时间
     * @param timeUnit 时间单位
     * @param clazz 目标类型 Class
     * @param <T> 目标类型泛型
     * @return 目标类型消息对象，超时返回 null
     * @throws InterruptedException 线程中断异常
     */
    public <T> T poll(String queueName, long timeout, TimeUnit timeUnit, Class<T> clazz) throws InterruptedException {
        Object raw = poll(queueName, timeout, timeUnit);
        return convertValue(raw, clazz);
    }

    /**
     * 无限阻塞拉取就绪队列消息（直至有到期消息可取）
     *
     * @param queueName 队列名称
     * @param <T> 消息泛型
     * @return 到期消息
     * @throws InterruptedException 线程中断异常
     */
    @SuppressWarnings("unchecked")
    public <T> T take(String queueName) throws InterruptedException {
        Assert.hasText(queueName, "queueName must not be blank");
        RBlockingQueue<Object> blockingQueue = getBlockingQueue(queueName);
        return (T) blockingQueue.take();
    }

    /**
     * 无限阻塞拉取就绪队列消息并自动转换为目标类型
     *
     * @param queueName 队列名称
     * @param clazz 目标类型 Class
     * @param <T> 目标类型泛型
     * @return 目标类型消息对象
     * @throws InterruptedException 线程中断异常
     */
    public <T> T take(String queueName, Class<T> clazz) throws InterruptedException {
        Object raw = take(queueName);
        return convertValue(raw, clazz);
    }

    /**
     * 查看就绪队列队头元素（不移除）
     *
     * @param queueName 队列名称
     * @param <T> 消息泛型
     * @return 队头消息
     */
    @SuppressWarnings("unchecked")
    public <T> T peek(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        RBlockingQueue<Object> blockingQueue = getBlockingQueue(queueName);
        return (T) blockingQueue.peek();
    }

    /**
     * 查看就绪队列队头元素并转换为目标类型（不移除）
     *
     * @param queueName 队列名称
     * @param clazz 目标类型 Class
     * @param <T> 目标类型泛型
     * @return 目标类型队头消息
     */
    public <T> T peek(String queueName, Class<T> clazz) {
        Object raw = peek(queueName);
        return convertValue(raw, clazz);
    }

    /**
     * 从队列中移除指定消息（同时尝试从延迟待触发集合与就绪队列中移除）
     *
     * @param queueName 队列名称
     * @param message 消息对象
     * @return 是否成功移除
     */
    public boolean remove(String queueName, Object message) {
        Assert.hasText(queueName, "queueName must not be blank");
        if (message == null) {
            return false;
        }
        RDelayedQueue<Object> delayedQueue = getDelayedQueue(queueName);
        boolean removedFromDelay = delayedQueue.remove(message);
        RBlockingQueue<Object> blockingQueue = getBlockingQueue(queueName);
        boolean removedFromBlock = blockingQueue.remove(message);
        return removedFromDelay || removedFromBlock;
    }

    /**
     * 获取就绪待消费队列元素个数
     *
     * @param queueName 队列名称
     * @return 已到期未消费数量
     */
    public int size(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        return getBlockingQueue(queueName).size();
    }

    /**
     * 获取延时中未到期触发的元素个数
     *
     * @param queueName 队列名称
     * @return 延时中的消息数量
     */
    public int delayedSize(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        return getDelayedQueue(queueName).size();
    }

    /**
     * 清空指定队列（同时清空就绪队列与延时队列）
     *
     * @param queueName 队列名称
     */
    public void clear(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        getBlockingQueue(queueName).clear();
        getDelayedQueue(queueName).clear();
    }

    /**
     * 销毁指定延时队列（注销 Redisson 内部轮询调度任务）
     *
     * @param queueName 队列名称
     */
    public void destroy(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        RDelayedQueue<Object> queue = delayedQueueMap.remove(queueName);
        if (queue != null) {
            queue.destroy();
        }
    }

    /**
     * 销毁全部受管的延时队列
     */
    public void destroyAll() {
        delayedQueueMap.forEach((name, queue) -> {
            try {
                queue.destroy();
            } catch (Exception ignored) {
                // 停机释放忽略异常
            }
        });
        delayedQueueMap.clear();
    }

    /**
     * 解析最终队列名称（自动附加全局 keyPrefix 隔离）
     *
     * @param queueName 原始队列名
     * @return 最终受管队列名
     */
    public String resolveQueueName(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        String globalPrefix = redisProperties != null ? redisProperties.getKeyPrefix() : null;
        if (StringUtils.hasText(globalPrefix) && !queueName.startsWith(globalPrefix)) {
            return globalPrefix + queueName;
        }
        return queueName;
    }

    /**
     * 解析死信队列名称
     *
     * @param queueName 原始队列名
     * @return 最终死信队列名
     */
    public String resolveDeadLetterQueueName(String queueName) {
        String baseName = resolveQueueName(queueName);
        String suffix = (redisProperties != null && redisProperties.getDelayQueue() != null
                && StringUtils.hasText(redisProperties.getDelayQueue().getDeadLetterSuffix()))
                ? redisProperties.getDelayQueue().getDeadLetterSuffix()
                : ":dlq";
        if (baseName.endsWith(suffix)) {
            return baseName;
        }
        return baseName + suffix;
    }

    /**
     * 将死信消息投递至死信队列
     *
     * @param queueName 原始队列名称
     * @param deadLetterMessage 死信消息包装体
     * @param <T> 载荷泛型
     */
    public <T> void sendToDeadLetterQueue(String queueName, DeadLetterMessage<T> deadLetterMessage) {
        Assert.hasText(queueName, "queueName must not be blank");
        Assert.notNull(deadLetterMessage, "deadLetterMessage must not be null");
        String dlqName = resolveDeadLetterQueueName(queueName);
        RBlockingQueue<Object> dlq = redissonClient.getBlockingQueue(dlqName);
        dlq.offer(deadLetterMessage);
    }

    /**
     * 将死信消息投递至死信队列（sendToDeadLetterQueue 简写别名）
     *
     * @param queueName 原始队列名称
     * @param deadLetterMessage 死信消息包装体
     * @param <T> 载荷泛型
     */
    public <T> void sendToDeadLetter(String queueName, DeadLetterMessage<T> deadLetterMessage) {
        sendToDeadLetterQueue(queueName, deadLetterMessage);
    }

    /**
     * 查看死信队列队首消息但不移出队列
     *
     * @param queueName 原始队列名称
     * @param <T> 消息泛型
     * @return 死信消息，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> DeadLetterMessage<T> peekDeadLetter(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        String dlqName = resolveDeadLetterQueueName(queueName);
        RBlockingQueue<Object> dlq = redissonClient.getBlockingQueue(dlqName);
        Object raw = dlq.peek();
        return convertValue(raw, DeadLetterMessage.class);
    }

    /**
     * 查看死信队列队首消息并转换载荷为目标类型，不移出队列
     *
     * @param queueName 原始队列名称
     * @param payloadClass 目标载荷类型 Class
     * @param <T> 目标载荷泛型
     * @return 类型安全的死信消息对象，不存在返回 null
     */
    public <T> DeadLetterMessage<T> peekDeadLetter(String queueName, Class<T> payloadClass) {
        return convertDeadLetterPayload(peekDeadLetter(queueName), payloadClass);
    }

    /**
     * 从死信队列拉取一条死信消息
     *
     * @param queueName 原始队列名称
     * @param <T> 消息泛型
     * @return 死信消息，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> DeadLetterMessage<T> pollDeadLetter(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        String dlqName = resolveDeadLetterQueueName(queueName);
        RBlockingQueue<Object> dlq = redissonClient.getBlockingQueue(dlqName);
        Object raw = dlq.poll();
        return convertValue(raw, DeadLetterMessage.class);
    }

    /**
     * 从死信队列拉取一条死信消息并转换载荷为目标类型
     *
     * @param queueName 原始队列名称
     * @param payloadClass 目标载荷类型 Class
     * @param <T> 目标载荷泛型
     * @return 类型安全的死信消息对象，不存在返回 null
     */
    public <T> DeadLetterMessage<T> pollDeadLetter(String queueName, Class<T> payloadClass) {
        return convertDeadLetterPayload(pollDeadLetter(queueName), payloadClass);
    }

    private <T> DeadLetterMessage<T> convertDeadLetterPayload(DeadLetterMessage<?> rawMessage, Class<T> payloadClass) {
        if (rawMessage == null) {
            return null;
        }
        T typedPayload = convertValue(rawMessage.payload(), payloadClass);
        return new DeadLetterMessage<>(
                rawMessage.originalQueue(),
                typedPayload,
                rawMessage.retryTimes(),
                rawMessage.errorMessage(),
                rawMessage.exceptionClass(),
                rawMessage.failedTime()
        );
    }

    /**
     * 获取死信队列积压消息数量
     *
     * @param queueName 原始队列名称
     * @return 死信数量
     */
    public int deadLetterSize(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        String dlqName = resolveDeadLetterQueueName(queueName);
        return redissonClient.getBlockingQueue(dlqName).size();
    }

    /**
     * 获取死信队列积压消息数量（deadLetterSize 兼容别名）
     *
     * @param queueName 原始队列名称
     * @return 死信数量
     */
    public int getDeadLetterSize(String queueName) {
        return deadLetterSize(queueName);
    }

    /**
     * 清空死信队列
     *
     * @param queueName 原始队列名称
     */
    public void clearDeadLetterQueue(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        String dlqName = resolveDeadLetterQueueName(queueName);
        redissonClient.getBlockingQueue(dlqName).clear();
    }

    /**
     * 清空死信队列（clearDeadLetterQueue 简写别名）
     *
     * @param queueName 原始队列名称
     */
    public void clearDeadLetter(String queueName) {
        clearDeadLetterQueue(queueName);
    }

    /**
     * 获取底层 RDelayedQueue 实例
     *
     * @param queueName 队列名称
     * @param <T> 元素类型
     * @return RDelayedQueue 实例
     */
    @SuppressWarnings("unchecked")
    public <T> RDelayedQueue<T> getDelayedQueue(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        String resolvedName = resolveQueueName(queueName);
        return (RDelayedQueue<T>) delayedQueueMap.computeIfAbsent(resolvedName, name -> {
            RBlockingQueue<Object> blockingQueue = redissonClient.getBlockingQueue(name);
            return redissonClient.getDelayedQueue(blockingQueue);
        });
    }

    /**
     * 获取底层就绪阻塞队列 RBlockingQueue 实例
     *
     * @param queueName 队列名称
     * @param <T> 元素类型
     * @return RBlockingQueue 实例
     */
    public <T> RBlockingQueue<T> getBlockingQueue(String queueName) {
        Assert.hasText(queueName, "queueName must not be blank");
        return redissonClient.getBlockingQueue(resolveQueueName(queueName));
    }

    /**
     * 安全类型映射转换
     */
    @SuppressWarnings("unchecked")
    public <T> T convertValue(Object raw, Class<T> clazz) {
        if (raw == null) {
            return null;
        }
        if (clazz == null || clazz.isInstance(raw)) {
            return (T) raw;
        }
        return objectMapper.convertValue(raw, clazz);
    }

    @Override
    public void destroy() {
        destroyAll();
    }
}
