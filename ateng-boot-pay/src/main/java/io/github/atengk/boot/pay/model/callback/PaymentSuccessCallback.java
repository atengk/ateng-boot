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
 * 标准化强类型支付成功异步回调事件模型
 * <p>
 * 经过网关验签、AES 解密与防重锁并发控制后，分发给业务方 {@link io.github.atengk.boot.pay.spi.PayCallbackListener} 的强类型领域事件，
 * 全域统一采用 {@link BigDecimal}（元）金额契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSuccessCallback implements Serializable {

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
     * 商户订单号 (业务方出站单号，outTradeNo)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号 (微信 TransactionId, 支付宝 TradeNo)
     */
    private String transactionId;

    /**
     * 实际支付金额 (单位：元，全域强类型 BigDecimal)
     */
    private BigDecimal amount;

    /**
     * 付款人标识 (微信用户 OpenId，支付宝买家 BuyerId / BuyerOpenId)
     */
    private String payerId;

    /**
     * 支付完成时间 (格式标准化字符串，如 "2026-10-23T22:30:00+08:00" 或 "2026-10-23 22:30:00")
     */
    private String payTime;

    /**
     * 标准化交易生命周期状态枚举
     */
    private PayTradeState tradeState;

    /**
     * 商户附加数据 / 回传数据 (统一下单时由业务方传入的 attach 或 passback_params 原样透传)
     */
    private String attach;

    /**
     * 渠道原始回调载荷对象 (微信为解密后的 Transaction 对象或完整报文，支付宝为完整请求 Map)
     */
    private Object rawPayload;
}
