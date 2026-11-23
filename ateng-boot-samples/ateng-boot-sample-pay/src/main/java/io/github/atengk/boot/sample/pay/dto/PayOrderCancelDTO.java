package io.github.atengk.boot.sample.pay.dto;

import io.github.atengk.boot.pay.enums.PayChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单或线下条码撤销冲正请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOrderCancelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    /**
     * 商户订单号
     */
    @NotBlank(message = "商户订单号不能为空")
    private String outTradeNo;

    /**
     * 渠道交易流水号（可选）
     */
    private String transactionId;

    /**
     * 多商户/多应用别名键（可选）
     */
    private String merchantKey;

    /**
     * 微信服务商特约子商户号（可选）
     */
    private String subMchId;

    /**
     * 支付宝服务商第三方应用授权令牌（可选）
     */
    private String appAuthToken;
}
