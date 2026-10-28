package io.github.atengk.boot.notification.exception;

import lombok.Getter;

import java.time.Duration;

/**
 * 协同通知频控拦截超限异常
 * 当出站速率超过平台限流阈值且等待超时时触发，主动防卫机器人账号免受封禁
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationRateLimitException extends NotificationException {

    private static final long serialVersionUID = 1L;

    /**
     * 触发频控的渠道标识
     */
    private final String channelName;

    /**
     * 限制的最大配额 (条/分钟)
     */
    private final Integer permitsPerMinute;

    /**
     * 最大等待排队时长
     */
    private final Duration waitDuration;

    public NotificationRateLimitException(String channelName, Integer permitsPerMinute, Duration waitDuration, String msg) {
        super(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED, msg);
        this.channelName = channelName;
        this.permitsPerMinute = permitsPerMinute;
        this.waitDuration = waitDuration;
    }

    public NotificationRateLimitException(String channelName, Integer permitsPerMinute, Duration waitDuration, String msg, Throwable cause) {
        super(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED, msg, cause);
        this.channelName = channelName;
        this.permitsPerMinute = permitsPerMinute;
        this.waitDuration = waitDuration;
    }
}
