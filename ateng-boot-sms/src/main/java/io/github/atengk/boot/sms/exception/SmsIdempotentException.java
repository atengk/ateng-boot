package io.github.atengk.boot.sms.exception;

import lombok.Getter;

/**
 * 短信出站发信幂等拦截异常
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Getter
public class SmsIdempotentException extends SmsException {

    private static final long serialVersionUID = 1L;

    /**
     * 目标手机号
     */
    private final String phone;

    /**
     * 幂等唯一指纹键
     */
    private final String idempotentKey;

    public SmsIdempotentException(String phone, String idempotentKey, String msg) {
        super(SmsResultCode.SMS_IDEMPOTENT_REJECTED, msg);
        this.phone = phone;
        this.idempotentKey = idempotentKey;
    }

    public SmsIdempotentException(String phone, String idempotentKey) {
        this(phone, idempotentKey, "发信请求在幂等窗口期内重复触发已拦截");
    }
}
