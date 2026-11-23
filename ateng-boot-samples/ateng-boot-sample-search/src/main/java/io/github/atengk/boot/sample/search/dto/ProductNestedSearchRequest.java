package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Nested SKU 规格独立作用域匹配请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductNestedSearchRequest {

    @NotBlank(message = "SKU 颜色不能为空")
    private String color;

    @NotNull(message = "最低价格不能为空")
    @DecimalMin(value = "0.0", message = "价格不能为负数")
    private BigDecimal minPrice;
}
