package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.pay.core.context.PayContextHolder;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 顶层统一退款请求入参
 * <p>
 * 严格固化部分退款四要素（{@link #outTradeNo}、{@link #outRefundNo}、{@link #totalAmount}、{@link #refundAmount}），
 * 杜绝多次部分退款引发的重复拦截与百倍金额资损风险。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedRefundRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标支付渠道 (WECHAT_PAY, ALIPAY，未传时优先从 PayContextHolder 获取)
     */
    private PayChannel channel;

    /**
     * 商户订单号 (原交易商户订单号，与 transactionId 二选一必填)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号 (微信 TransactionId, 支付宝 TradeNo，可选)
     */
    private String transactionId;

    /**
     * 商户退款单号 (本次退款在商户系统内部的唯一业务单号，必填)
     */
    private String outRefundNo;

    /**
     * 原订单总金额 (单位：元，全域强类型 BigDecimal，必填)
     */
    private BigDecimal totalAmount;

    /**
     * 本次申请退款金额 (单位：元，全域强类型 BigDecimal，必填，必须 <= totalAmount)
     */
    private BigDecimal refundAmount;

    /**
     * 退款原因或说明 (可选)
     */
    private String reason;

    /**
     * 异步接收退款结果通知的回调地址 (可选，未传时取配置的默认地址)
     */
    private String notifyUrl;

    /**
     * 货币代码 (默认 CNY)
     */
    @Builder.Default
    private String currency = "CNY";

    /**
     * 商户配置别名或应用别名 (可选，用于多商户/多应用路由)
     */
    private String merchantKey;

    /**
     * 服务商模式特约子商户号 (微信 sub_mchid)
     */
    private String subMchId;

    /**
     * 服务商模式第三方应用授权令牌 (支付宝 app_auth_token)
     */
    private String appAuthToken;

    /**
     * 针对部分退款四要素执行前置快速失败校验
     *
     * @throws PayException 当缺少关键退款参数或金额非法时抛出
     */
    public void validate() {
        if ((outTradeNo == null || outTradeNo.isBlank()) && (transactionId == null || transactionId.isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "退款请求缺少商户原订单号 (outTradeNo) 或渠道交易号 (transactionId)");
        }
        if (outRefundNo == null || outRefundNo.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "退款请求商户退款单号 (outRefundNo) 不能为空");
        }
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "退款请求原订单总金额 (totalAmount) 必须大于 0");
        }
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "退款请求本次退款金额 (refundAmount) 必须大于 0");
        }
        if (refundAmount.compareTo(totalAmount) > 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "本次退款金额 (refundAmount: " + refundAmount + ") 不能大于原订单总金额 (totalAmount: " + totalAmount + ")");
        }
    }
}
