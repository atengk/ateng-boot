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
 * 商家转账主动撤销请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayTransferCancelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    /**
     * 多商户/多应用别名键（可选）
     */
    private String merchantKey;

    /**
     * 商户转账唯一单号
     */
    @NotBlank(message = "商户转账单号不能为空")
    private String outBizNo;
}
