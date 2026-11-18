package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 支付宝统一收单交易分账结算请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayOrderSettleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String appAuthToken;

    @NotBlank(message = "分账请求流水号不能为空")
    private String outRequestNo;

    @NotBlank(message = "支付宝交易号不能为空")
    private String tradeNo;

    @Valid
    @NotEmpty(message = "分账明细参数列表不能为空")
    private List<RoyaltyDetailItem> royaltyParameters;

    /**
     * 支付宝单条分账结算明细项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoyaltyDetailItem implements Serializable {

        private static final long serialVersionUID = 1L;

        @NotBlank(message = "收入方类型不能为空")
        private String transInType;

        @NotBlank(message = "收入方账号不能为空")
        private String transIn;

        @NotNull(message = "分账金额(元)不能为空")
        @DecimalMin(value = "0.01", message = "分账金额必须大于 0 元")
        private BigDecimal amount;

        private String desc;

        private String royaltyType;
    }
}
