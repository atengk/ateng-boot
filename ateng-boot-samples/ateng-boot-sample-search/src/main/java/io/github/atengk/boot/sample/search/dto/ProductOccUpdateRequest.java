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
 * 基于 OCC 乐观并发控制的价格更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductOccUpdateRequest {

    @NotBlank(message = "商品ID不能为空")
    private String id;

    @NotBlank(message = "分片路由键（品牌）不能为空")
    private String brand;

    @NotNull(message = "更新价格不能为空")
    @DecimalMin(value = "0.0", message = "价格不能为负数")
    private BigDecimal price;

    @NotNull(message = "期望 seq_no 不能为空")
    private Long seqNo;

    @NotNull(message = "期望 primary_term 不能为空")
    private Long primaryTerm;
}
