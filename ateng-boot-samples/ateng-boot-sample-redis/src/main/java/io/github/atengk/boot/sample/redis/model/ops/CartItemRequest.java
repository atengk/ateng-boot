package io.github.atengk.boot.sample.redis.model.ops;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 购物车商品添加/更新请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemRequest {

    @NotBlank(message = "用户 ID 不能为空")
    private String userId;

    @NotBlank(message = "商品 SKU 不能为空")
    private String skuId;

    @NotBlank(message = "商品名称不能为空")
    private String skuName;

    @NotNull(message = "加购数量不能为空")
    @Min(value = 1, message = "加购数量必须大于等于 1")
    private Integer quantity;

    @NotNull(message = "商品单价不能为空")
    @DecimalMin(value = "0.01", message = "单价必须大于 0")
    private BigDecimal price;
}
