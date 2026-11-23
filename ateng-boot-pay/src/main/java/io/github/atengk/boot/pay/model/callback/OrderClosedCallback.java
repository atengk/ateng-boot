package io.github.atengk.boot.pay.model.callback;

import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayTradeState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 标准化强类型订单关闭异步回调事件模型
 * <p>
 * 经过网关验签、AES 解密与并发防重锁控制后，针对渠道明确通知的关单/撤销终态（如微信 {@code CLOSED}/{@code REVOKED}、
 * 未发生退款的支付宝 {@code TRADE_CLOSED}），分发给业务方 {@link io.github.atengk.boot.pay.spi.PayCallbackListener#onOrderClosed} 的领域事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderClosedCallback implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    private PayChannel channel;

    /**
     * 商户标识键或应用标识
     */
    private String merchantKey;

    /**
     * 商户订单号 (outTradeNo)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号 (微信 TransactionId, 支付宝 TradeNo)
     */
    private String transactionId;

    /**
     * 订单金额 (单位：元，全域强类型 BigDecimal，可能为 null)
     */
    private BigDecimal amount;

    /**
     * 订单关闭时间 (格式标准化字符串)
     */
    private String closeTime;

    /**
     * 标准化交易生命周期状态枚举 (CLOSED 或 REVOKED)
     */
    private PayTradeState tradeState;

    /**
     * 渠道原生状态码 (如 CLOSED、REVOKED、TRADE_CLOSED)
     */
    private String rawTradeState;

    /**
     * 关单原因或渠道描述
     */
    private String closeReason;

    /**
     * 商户附加数据 / 回传数据
     */
    private String attach;

    /**
     * 渠道原始回调载荷对象
     */
    private Object rawPayload;
}
