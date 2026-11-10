package io.github.atengk.boot.redis.delay.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 延时队列死信消息实体
 * 封装在重试次数耗尽后转移至死信队列的原始消息及失败诊断上下文
 *
 * @param originalQueue 原始延时队列名称
 * @param payload 原始业务载荷对象
 * @param retryTimes 经历的重试次数
 * @param errorMessage 最终失败异常信息
 * @param exceptionClass 最终失败异常类全限定名
 * @param failedTime 失败转入死信的时间戳
 * @param replayCount 已受控重放次数（防二次死信雪崩）
 * @param <T> 业务载荷泛型
 * @author Ateng
 * @since 2026-10-19
 */
public record DeadLetterMessage<T>(
        String originalQueue,
        T payload,
        int retryTimes,
        String errorMessage,
        String exceptionClass,
        LocalDateTime failedTime,
        int replayCount
) implements Serializable {

    /**
     * 兼容性构造器（默认重放次数为 0）
     */
    public DeadLetterMessage(String originalQueue,
                             T payload,
                             int retryTimes,
                             String errorMessage,
                             String exceptionClass,
                             LocalDateTime failedTime) {
        this(originalQueue, payload, retryTimes, errorMessage, exceptionClass, failedTime, 0);
    }

    /**
     * 构建重放计数递增 1 的死信副本
     *
     * @return 递增重放计数后的新实体
     */
    public DeadLetterMessage<T> incrementReplay() {
        return new DeadLetterMessage<>(
                originalQueue,
                payload,
                retryTimes,
                errorMessage,
                exceptionClass,
                failedTime,
                replayCount + 1
        );
    }
}
