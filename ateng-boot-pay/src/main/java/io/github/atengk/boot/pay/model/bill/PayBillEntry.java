package io.github.atengk.boot.pay.model.bill;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 财务对账单明细记录项
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBillEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY / ALIPAY)
     */
    private PayChannel channel;

    /**
     * 交易时间 (如 2026-10-24 09:15:00)
     */
    private String tradeTime;

    /**
     * 渠道交易流水号 (微信订单号 / 支付宝交易号)
     */
    private String transactionId;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 渠道退款单号 (微信退款单号)
     */
    private String refundId;

    /**
     * 商户退款单号 (微信商户退款单号 / 支付宝退款批次号)
     */
    private String outRefundNo;

    /**
     * 交易类型 (如 JSAPI, NATIVE, 交易, 在线支付, 当面付等)
     */
    private String tradeType;

    /**
     * 交易状态 (如 SUCCESS, REFUND, 交易成功, 退款成功等)
     */
    private String tradeState;

    /**
     * 订单总金额 (元)
     */
    private BigDecimal totalAmount;

    /**
     * 商家应结/实收金额 (元)
     */
    private BigDecimal settlementAmount;

    /**
     * 退款金额 (元)
     */
    private BigDecimal refundAmount;

    /**
     * 手续费 / 服务费金额 (元)
     */
    private BigDecimal serviceFee;

    /**
     * 手续费率 (如 0.60%)
     */
    private String feeRate;

    /**
     * 商品名称 / 标题
     */
    private String subject;

    /**
     * 扩展与原始列数据字典
     */
    @Builder.Default
    private Map<String, String> rawValues = new LinkedHashMap<>();
}
