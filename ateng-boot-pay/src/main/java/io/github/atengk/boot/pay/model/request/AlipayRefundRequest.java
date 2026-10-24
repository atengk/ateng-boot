package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付宝专有退款与退款查询入参
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayRefundRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付宝应用别名或 AppId (用于多应用路由)
     */
    private String appKey;

    /**
     * 商户订单号 (原交易商户订单号，与 tradeNo 二选一必填)
     */
    private String outTradeNo;

    /**
     * 支付宝交易号 (原交易支付宝流水号，可选)
     */
    private String tradeNo;

    /**
     * 商户退款单号 (部分退款标识，底座自动映射为支付宝 out_request_no，必填)
     */
    private String outRefundNo;

    /**
     * 原订单总金额 (单位：元，全域强类型 BigDecimal，必填)
     */
    private BigDecimal totalAmount;

    /**
     * 本次申请退款金额 (单位：元，全域强类型 BigDecimal，必填)
     */
    private BigDecimal refundAmount;

    /**
     * 退款原因或说明 (可选)
     */
    private String reason;

    /**
     * 异步通知地址 (可选)
     */
    private String notifyUrl;

    /**
     * 第三方应用授权令牌 (服务商模式可选，app_auth_token)
     */
    private String appAuthToken;

    /**
     * 校验支付宝退款参数
     */
    public void validate() {
        if ((outTradeNo == null || outTradeNo.isBlank()) && (tradeNo == null || tradeNo.isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝退款请求缺少商户原订单号 (outTradeNo) 或支付宝交易号 (tradeNo)");
        }
        if (outRefundNo == null || outRefundNo.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝退款请求商户退款单号 (outRefundNo) 不能为空");
        }
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝退款请求原订单总金额 (totalAmount) 必须大于 0");
        }
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝退款请求本次退款金额 (refundAmount) 必须大于 0");
        }
        if (refundAmount.compareTo(totalAmount) > 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "本次退款金额 (refundAmount: " + refundAmount + ") 不能大于原订单总金额 (totalAmount: " + totalAmount + ")");
        }
    }
}
