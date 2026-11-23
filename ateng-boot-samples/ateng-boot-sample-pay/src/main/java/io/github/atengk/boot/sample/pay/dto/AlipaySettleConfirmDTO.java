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
 * 支付宝统一收单交易结算确认请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipaySettleConfirmDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String appAuthToken;

    @NotBlank(message = "外部确认请求号不能为空")
    private String outRequestNo;

    @NotBlank(message = "支付宝交易号不能为空")
    private String tradeNo;

    @NotNull(message = "结算金额不能为空")
    @DecimalMin(value = "0.01", message = "结算金额必须大于 0 元")
    private BigDecimal amount;

    @NotBlank(message = "结算周期不能为空")
    @Builder.Default
    private String settlePeriodTime = "1d";
}
