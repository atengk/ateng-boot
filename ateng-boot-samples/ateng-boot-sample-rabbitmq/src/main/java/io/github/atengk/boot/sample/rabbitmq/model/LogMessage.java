package io.github.atengk.boot.sample.rabbitmq.model;

/**
 * 层次通配系统日志消息
 *
 * @author Ateng
 * @since 2026-10-21
 */
public record LogMessage(
        String level,
        String serviceName,
        String content
) {
}
