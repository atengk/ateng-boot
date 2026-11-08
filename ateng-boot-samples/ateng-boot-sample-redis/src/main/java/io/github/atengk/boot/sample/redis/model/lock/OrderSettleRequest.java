package io.github.atengk.boot.sample.redis.model.lock;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单结算调库存（跨服务重入演练）请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSettleRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    @NotNull(message = "结算数量不能为空")
    @Min(value = 1, message = "结算数量必须大于 0")
    private Integer quantity;
}
