package io.github.atengk.boot.sms.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

/**
 * 短信基础业务异常
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public SmsException(IResultCode resultCode) {
        super(resultCode);
    }

    public SmsException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    public SmsException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public SmsException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }

    public SmsException(String msg, Throwable cause) {
        super(SmsResultCode.SMS_SEND_ERROR, msg, cause);
    }
}
