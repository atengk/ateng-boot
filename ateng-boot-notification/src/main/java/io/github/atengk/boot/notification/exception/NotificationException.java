package io.github.atengk.boot.notification.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

/**
 * 协同通知基础业务异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public NotificationException(IResultCode resultCode) {
        super(resultCode);
    }

    public NotificationException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    public NotificationException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public NotificationException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }

    public NotificationException(String msg, Throwable cause) {
        super(NotificationResultCode.NOTIFICATION_SEND_ERROR, msg, cause);
    }
}
