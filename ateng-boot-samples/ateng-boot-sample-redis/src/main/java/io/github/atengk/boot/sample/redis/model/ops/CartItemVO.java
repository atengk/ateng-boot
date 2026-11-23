package io.github.atengk.boot.sample.redis.model.ops;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 购物车单品项视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemVO {

    private String skuId;
    private String skuName;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal subtotal;
}
