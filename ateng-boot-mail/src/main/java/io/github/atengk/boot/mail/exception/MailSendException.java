package io.github.atengk.boot.mail.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

/**
 * 邮件发送语义化业务异常
 * 统管底层 JavaMail 异常因果链并赋予清晰的错误码
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
public class MailSendException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public MailSendException(IResultCode resultCode) {
        super(resultCode);
    }

    public MailSendException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    public MailSendException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public MailSendException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }

    public MailSendException(String msg, Throwable cause) {
        super(MailResultCode.MAIL_SEND_ERROR, msg, cause);
    }
}
