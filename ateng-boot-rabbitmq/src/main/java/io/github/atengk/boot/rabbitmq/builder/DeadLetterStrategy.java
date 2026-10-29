package io.github.atengk.boot.rabbitmq.builder;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * RabbitMQ Quorum 队列死信策略枚举
 * 对应 Quorum 队列的 {@code x-dead-letter-strategy} 参数
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Getter
@RequiredArgsConstructor
public enum DeadLetterStrategy {

    /**
     * 至少一次死信策略 (Quorum 队列官方推荐标准，确保死信可靠投递至死信交换机)
     */
    AT_LEAST_ONCE("at-least-once");

    private final String value;
}
