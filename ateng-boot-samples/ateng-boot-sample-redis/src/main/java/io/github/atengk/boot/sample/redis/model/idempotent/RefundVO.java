package io.github.atengk.boot.sample.redis.model.idempotent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 退款结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String refundNo;
    private BigDecimal refundAmount;
    private Boolean success;
    private String message;
}
