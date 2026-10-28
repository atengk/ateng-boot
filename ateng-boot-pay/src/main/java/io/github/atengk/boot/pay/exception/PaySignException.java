package io.github.atengk.boot.pay.exception;

import io.github.atengk.boot.pay.enums.PayResultCode;

/**
 * 支付签名计算或回调验签失败异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PaySignException extends PayException {

    private static final long serialVersionUID = 1L;

    public PaySignException(String message) {
        super(PayResultCode.PAY_SIGN_VERIFY_ERROR, message);
    }

    public PaySignException(String message, Throwable cause) {
        super(PayResultCode.PAY_SIGN_VERIFY_ERROR, message, cause);
    }

    public PaySignException(io.github.atengk.boot.core.code.IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public PaySignException(io.github.atengk.boot.core.code.IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }
}
