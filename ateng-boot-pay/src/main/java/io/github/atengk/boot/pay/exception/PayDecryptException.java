package io.github.atengk.boot.pay.exception;

import io.github.atengk.boot.pay.enums.PayResultCode;

/**
 * 支付异步通知报文密文解密失败异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PayDecryptException extends PayException {

    private static final long serialVersionUID = 1L;

    public PayDecryptException(String message) {
        super(PayResultCode.PAY_DECRYPT_ERROR, message);
    }

    public PayDecryptException(String message, Throwable cause) {
        super(PayResultCode.PAY_DECRYPT_ERROR, message, cause);
    }
}
