package io.github.atengk.boot.redis.delay.spi;

import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;

/**
 * 延时队列终态消费失败处理扩展点（SPI）
 * 当延时任务重试耗尽时触发回调，供业务方接入外部报警（如钉钉、飞书）、持久化入库或异常转储
 *
 * @author Ateng
 * @since 2026-10-19
 */
@FunctionalInterface
public interface DelayedQueueFailureHandler {

    /**
     * 消费失败终态回调
     *
     * @param deadLetterMessage 死信消息体（包含原始队列、载荷、重试次数、错误信息与时间戳）
     * @param cause 最终导致的未捕获异常
     */
    void onFailed(DeadLetterMessage<?> deadLetterMessage, Throwable cause);
}
