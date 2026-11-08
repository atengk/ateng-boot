package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 乐观锁并发扣减/改价入参传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkuDeductDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 SKU 主键 ID
     */
    @NotNull(message = "SKU ID 不能为空")
    private Long skuId;

    /**
     * 扣减降价金额
     */
    @NotNull(message = "扣减金额不能为空")
    @DecimalMin(value = "0.01", message = "扣减金额必须大于 0")
    private BigDecimal deductAmount;
}
