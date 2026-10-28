package io.github.atengk.boot.pay.model.response;

import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayRefundState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 统一退款与退款查询响应出参
 * <p>
 * 统一抹平微信支付与支付宝退款出参差异，强类型承载商户退款单号、渠道流水号、原订单总额、本次退款金额及统一退款状态机。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayRefundResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    private PayChannel channel;

    /**
     * 商户原订单号 (outTradeNo)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号 (微信 transaction_id, 支付宝 trade_no)
     */
    private String transactionId;

    /**
     * 商户退款单号 (outRefundNo)
     */
    private String outRefundNo;

    /**
     * 渠道退款流水号 (微信 refund_id)
     */
    private String refundId;

    /**
     * 是否受理或退款成功
     */
    private boolean success;

    /**
     * 标准化统一退款生命周期状态枚举
     */
    private PayRefundState refundState;

    /**
     * 渠道原生退款状态码 (如 SUCCESS, PROCESSING, REFUND_SUCCESS 等)
     */
    private String rawRefundState;

    /**
     * 状态描述或失败原因
     */
    private String refundStateDesc;

    /**
     * 原订单总金额 (单位：元，全域强类型 BigDecimal)
     */
    private BigDecimal totalAmount;

    /**
     * 本次退款金额 (单位：元，全域强类型 BigDecimal)
     */
    private BigDecimal refundAmount;

    /**
     * 退款入账账户 (如微信退回零钱、银行卡等)
     */
    private String userReceivedAccount;

    /**
     * 退款成功时间 (格式化字符串或 RFC3339)
     */
    private String successTime;

    /**
     * 渠道底层原始响应对象
     */
    private Object rawResponse;
}
