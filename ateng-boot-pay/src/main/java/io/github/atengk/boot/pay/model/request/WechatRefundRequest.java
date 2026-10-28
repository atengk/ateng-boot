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
 * 微信支付专有退款与退款查询入参
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatRefundRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户配置别名或商户号 (用于多商户路由)
     */
    private String merchantKey;

    /**
     * 商户订单号 (原交易商户订单号)
     */
    private String outTradeNo;

    /**
     * 微信支付订单号 (原交易微信订单号，可选)
     */
    private String transactionId;

    /**
     * 商户退款单号 (本次退款唯一单号，必填)
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
     * 异步接收微信支付退款结果通知的回调地址 (可选)
     */
    private String notifyUrl;

    /**
     * 货币代码 (默认 CNY)
     */
    @Builder.Default
    private String currency = "CNY";

    /**
     * 特约子商户号 (服务商模式专用，sub_mchid)
     */
    private String subMchId;

    /**
     * 退款出资账户 (可选，如 AVAILABLE 可用余额账户)
     */
    private String fundsAccount;

    /**
     * 校验微信退款参数
     */
    public void validate() {
        if ((outTradeNo == null || outTradeNo.isBlank()) && (transactionId == null || transactionId.isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信退款请求缺少商户原订单号 (outTradeNo) 或微信订单号 (transactionId)");
        }
        if (outRefundNo == null || outRefundNo.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信退款请求商户退款单号 (outRefundNo) 不能为空");
        }
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信退款请求原订单总金额 (totalAmount) 必须大于 0");
        }
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信退款请求本次退款金额 (refundAmount) 必须大于 0");
        }
        if (refundAmount.compareTo(totalAmount) > 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "本次退款金额 (refundAmount: " + refundAmount + ") 不能大于原订单总金额 (totalAmount: " + totalAmount + ")");
        }
    }
}
