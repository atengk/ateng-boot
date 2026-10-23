package io.github.atengk.boot.pay.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.pay.enums.PayResultCode;
import lombok.Getter;

/**
 * 支付渠道明确返回业务错误异常
 * <p>
 * 保留渠道底层返回的原始错误代码（如微信 ORDERPAID、NOTENOUGH，支付宝 ACQ.TOTAL_FEE_EXCEED 等）与错误描述。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class PayTradeException extends PayException {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道原始错误代码
     */
    private final String rawErrorCode;

    /**
     * 渠道原始错误描述
     */
    private final String rawErrorMessage;

    public PayTradeException(String rawErrorCode, String rawErrorMessage) {
        super(PayResultCode.PAY_TRADE_STATE_INVALID, "[" + rawErrorCode + "] " + rawErrorMessage);
        this.rawErrorCode = rawErrorCode;
        this.rawErrorMessage = rawErrorMessage;
    }

    public PayTradeException(IResultCode resultCode, String rawErrorCode, String rawErrorMessage) {
        super(resultCode, "[" + rawErrorCode + "] " + rawErrorMessage);
        this.rawErrorCode = rawErrorCode;
        this.rawErrorMessage = rawErrorMessage;
    }

    public PayTradeException(IResultCode resultCode, String rawErrorCode, String rawErrorMessage, Throwable cause) {
        super(resultCode, "[" + rawErrorCode + "] " + rawErrorMessage, cause);
        this.rawErrorCode = rawErrorCode;
        this.rawErrorMessage = rawErrorMessage;
    }
}
