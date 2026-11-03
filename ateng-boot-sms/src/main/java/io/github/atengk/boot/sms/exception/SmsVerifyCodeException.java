package io.github.atengk.boot.sms.exception;

import lombok.Getter;

/**
 * 验证码核验异常
 * 结构化记录剩余可重试次数与冻结作废标记
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsVerifyCodeException extends SmsException {

    private static final long serialVersionUID = 1L;

    /**
     * 剩余可重试次数
     */
    private final int remainingAttempts;

    /**
     * 是否已被冻结作废
     */
    private final boolean frozen;

    public SmsVerifyCodeException(String msg) {
        super(SmsResultCode.SMS_VERIFY_CODE_INVALID, msg);
        this.remainingAttempts = 0;
        this.frozen = false;
    }

    public SmsVerifyCodeException(String msg, int remainingAttempts, boolean frozen) {
        super(frozen ? SmsResultCode.SMS_VERIFY_CODE_FROZEN : SmsResultCode.SMS_VERIFY_CODE_INVALID, msg);
        this.remainingAttempts = remainingAttempts;
        this.frozen = frozen;
    }

    public SmsVerifyCodeException(String msg, Throwable cause) {
        super(SmsResultCode.SMS_SEND_ERROR, msg, cause);
        this.remainingAttempts = 0;
        this.frozen = false;
    }
}
