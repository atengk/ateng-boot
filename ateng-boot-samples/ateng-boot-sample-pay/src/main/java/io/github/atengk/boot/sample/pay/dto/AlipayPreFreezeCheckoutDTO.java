package io.github.atengk.boot.sample.pay.dto;

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
 * 支付宝前置分账冻结收单请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayPreFreezeCheckoutDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String appAuthToken;

    @NotBlank(message = "商户订单号不能为空")
    private String outTradeNo;

    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额不能小于 0.01 元")
    private BigDecimal amount;

    @NotBlank(message = "订单标题不能为空")
    private String subject;

    /**
     * 是否开启前置分账冻结（默认为 true）
     */
    @Builder.Default
    private Boolean royaltyFreeze = Boolean.TRUE;
}
