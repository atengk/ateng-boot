package io.github.atengk.boot.pay.model.callback;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 标准化强类型退款成功异步回调事件模型
 * <p>
 * 经过网关验签、AES 解密与防重锁并发控制后，分发给业务方 {@link io.github.atengk.boot.pay.spi.PayCallbackListener} 的退款领域事件，
 * 全域统一采用 {@link BigDecimal}（元）金额契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundSuccessCallback implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    private PayChannel channel;

    /**
     * 商户标识键或应用标识 (如 default, app1 等)
     */
    private String merchantKey;

    /**
     * 原支付商户订单号 (业务方出站单号，outTradeNo)
     */
    private String outTradeNo;

    /**
     * 商户退款单号 (outRefundNo)
     */
    private String outRefundNo;

    /**
     * 渠道原支付交易流水号
     */
    private String transactionId;

    /**
     * 渠道退款流水号 (微信 refund_id, 支付宝 refund_no)
     */
    private String refundId;

    /**
     * 本次退款金额 (单位：元，全域强类型 BigDecimal)
     */
    private BigDecimal refundAmount;

    /**
     * 原订单总金额 (单位：元)
     */
    private BigDecimal totalAmount;

    /**
     * 退款完成时间 (格式标准化字符串)
     */
    private String refundTime;

    /**
     * 退款状态描述
     */
    private String refundStatus;

    /**
     * 渠道原始回调载荷对象
     */
    private Object rawPayload;
}
