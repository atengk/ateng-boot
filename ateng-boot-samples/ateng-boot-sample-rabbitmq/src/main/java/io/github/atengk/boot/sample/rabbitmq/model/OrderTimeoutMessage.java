package io.github.atengk.boot.sample.rabbitmq.model;

/**
 * 订单超时自动取消延迟消息
 *
 * @author Ateng
 * @since 2026-10-21
 */
public record OrderTimeoutMessage(
        String orderId,
        long timeoutSeconds,
        String reason
) {
}
