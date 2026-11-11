package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品库存局部更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPartialUpdateRequest {

    @NotBlank(message = "商品ID不能为空")
    private String id;

    @NotBlank(message = "分片路由键（品牌）不能为空")
    private String brand;

    @NotNull(message = "更新库存数不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;
}
