package io.github.atengk.boot.pay.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.pay.enums.PayResultCode;

/**
 * 支付模块顶层统一受控业务异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PayException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public PayException(String message) {
        super(PayResultCode.PAY_GATEWAY_ERROR, message);
    }

    public PayException(String message, Throwable cause) {
        super(PayResultCode.PAY_GATEWAY_ERROR, message, cause);
    }

    public PayException(IResultCode resultCode) {
        super(resultCode);
    }

    public PayException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public PayException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }

    public PayException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }
}
