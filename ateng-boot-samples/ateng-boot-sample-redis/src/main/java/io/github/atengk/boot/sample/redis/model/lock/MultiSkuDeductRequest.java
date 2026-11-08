package io.github.atengk.boot.sample.redis.model.lock;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 组合套餐多 SKU 联锁扣减请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiSkuDeductRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "组合套餐 ID 不能为空")
    private String comboId;

    @NotEmpty(message = "商品 ID 列表不能为空")
    private List<Long> productIds;

    @NotNull(message = "扣减数量不能为空")
    @Min(value = 1, message = "扣减数量必须大于 0")
    private Integer quantity;
}
