package io.github.atengk.boot.sample.observability.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存扣减请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDeductRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 商品编号
     */
    @NotBlank(message = "商品编号不能为空")
    private String productId;

    /**
     * 扣减库存数量
     */
    @NotNull(message = "扣减数量不能为空")
    @Min(value = 1, message = "扣减数量至少为 1")
    private Integer count;
}
