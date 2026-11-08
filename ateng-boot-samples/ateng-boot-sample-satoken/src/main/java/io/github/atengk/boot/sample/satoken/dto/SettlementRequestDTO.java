package io.github.atengk.boot.sample.satoken.dto;

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
 * 财务大额资金结算请求传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 结算单号
     */
    @NotBlank(message = "结算单号不能为空")
    private String settlementNo;

    /**
     * 结算金额
     */
    @NotNull(message = "结算金额不能为空")
    @DecimalMin(value = "0.01", message = "结算金额必须大于 0")
    private BigDecimal amount;

    /**
     * 收款账户
     */
    @NotBlank(message = "收款账户不能为空")
    private String recipientAccount;
}
