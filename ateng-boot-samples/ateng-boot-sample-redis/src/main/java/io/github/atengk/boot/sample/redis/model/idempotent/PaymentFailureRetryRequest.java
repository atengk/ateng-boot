package io.github.atengk.boot.sample.redis.model.idempotent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模拟瞬时异常后重试请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailureRetryRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "支付流水号不能为空")
    private String paymentNo;

    @NotNull(message = "支付金额不能为空")
    @Positive(message = "支付金额必须大于 0")
    private BigDecimal amount;

    @NotNull(message = "是否模拟失败标志不能为空")
    private Boolean shouldFail;
}
