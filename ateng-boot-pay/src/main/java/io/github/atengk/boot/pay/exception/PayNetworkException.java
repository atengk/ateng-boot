package io.github.atengk.boot.pay.exception;

import io.github.atengk.boot.pay.enums.PayResultCode;

/**
 * 支付网关通信超时或网络连接失败异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PayNetworkException extends PayException {

    private static final long serialVersionUID = 1L;

    public PayNetworkException(String message) {
        super(PayResultCode.PAY_GATEWAY_ERROR, message);
    }

    public PayNetworkException(String message, Throwable cause) {
        super(PayResultCode.PAY_GATEWAY_ERROR, message, cause);
    }
}
