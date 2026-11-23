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
 * 退款防重核销请求传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "退款单号不能为空")
    private String refundNo;

    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须大于 0")
    private BigDecimal refundAmount;

    private String reason;
}
