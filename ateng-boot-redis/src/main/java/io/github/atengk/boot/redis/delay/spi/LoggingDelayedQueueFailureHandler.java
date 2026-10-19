package io.github.atengk.boot.redis.delay.spi;

import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认延时队列消费失败处理器
 * 记录包含完整诊断上下文的 ERROR 级别审计日志
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public class LoggingDelayedQueueFailureHandler implements DelayedQueueFailureHandler {

    @Override
    public void onFailed(DeadLetterMessage<?> deadLetterMessage, Throwable cause) {
        if (deadLetterMessage == null) {
            log.error("Redis 延时队列消费失败: deadLetterMessage 为空", cause);
            return;
        }
        log.error("Redis 延时队列任务消费失败并已转入死信队列: originalQueue={}, retryTimes={}, failedTime={}, error={}",
                deadLetterMessage.originalQueue(),
                deadLetterMessage.retryTimes(),
                deadLetterMessage.failedTime(),
                deadLetterMessage.errorMessage(),
                cause);
    }
}
