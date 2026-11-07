package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付报文加密与验签请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCryptoRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 交易订单流水号
     */
    @NotBlank(message = "交易流水号不能为空")
    private String tradeNo;

    /**
     * 支付金额
     */
    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额最小为 0.01")
    private BigDecimal amount;

    /**
     * 支付渠道代码 (如 WXPAY / ALIPAY)
     */
    @NotBlank(message = "支付渠道不能为空")
    private String channel;

    /**
     * 开放平台商户 API 密钥
     */
    @NotBlank(message = "商户密钥不能为空")
    private String secretKey;

    /**
     * 敏感银行报文或交易载荷明文
     */
    @NotBlank(message = "交易明文载荷不能为空")
    private String rawPayload;
}
