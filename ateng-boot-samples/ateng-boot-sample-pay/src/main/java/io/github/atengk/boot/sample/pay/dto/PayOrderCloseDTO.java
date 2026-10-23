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
 * 未付款订单主动关单请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOrderCloseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    /**
     * 商户订单号 (业务唯一单号)
     */
    @NotBlank(message = "商户订单号不能为空")
    private String outTradeNo;

    /**
     * 多商户/多应用别名键
     */
    private String merchantKey;
}
