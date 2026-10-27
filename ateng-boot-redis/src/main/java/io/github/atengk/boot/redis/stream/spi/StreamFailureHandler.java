package io.github.atengk.boot.redis.stream.spi;

import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;

/**
 * Redis Stream 终态消费失败处理 SPI 扩展点
 * 当消费重试耗尽并将死信消息写入 {stream}:dlq 后触发此回调
 *
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface StreamFailureHandler {

    /**
     * 消费失败终态回调
     *
     * @param deadLetterMessage 死信消息结构体
     */
    void onFailure(StreamDeadLetterMessage deadLetterMessage);
}
