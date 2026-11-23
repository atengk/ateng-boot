package io.github.atengk.boot.sms.exception;

import lombok.Getter;

/**
 * 短信频控防刷拦截异常
 * 结构化携带客户端需等待的重试冷却秒数
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsRateLimitException extends SmsException {

    private static final long serialVersionUID = 1L;

    /**
     * 需等待的重试间隔秒数
     */
    private final long retryAfterSeconds;

    public SmsRateLimitException(String msg) {
        super(SmsResultCode.SMS_RATE_LIMIT_EXCEEDED, msg);
        this.retryAfterSeconds = 0;
    }

    public SmsRateLimitException(String msg, long retryAfterSeconds) {
        super(SmsResultCode.SMS_RATE_LIMIT_EXCEEDED, msg);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
