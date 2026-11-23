package io.github.atengk.boot.sample.redis.model.ops;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品榜单热度加分请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductScoreRequest {

    @NotBlank(message = "商品 ID 不能为空")
    private String productId;

    @NotBlank(message = "商品名称不能为空")
    private String productName;

    @NotNull(message = "分值增量不能为空")
    @DecimalMin(value = "0.1", message = "分值增量必须大于 0")
    private Double deltaScore;
}
