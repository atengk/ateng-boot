package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品新增或保存请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSaveRequest {

    private String id;

    @NotBlank(message = "商品标题不能为空")
    private String title;

    private String description;

    private String category;

    @NotBlank(message = "商品品牌不能为空")
    private String brand;

    private List<String> tags;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.0", message = "价格不能为负数")
    private BigDecimal price;

    private Integer stock;

    private String status;

    private GeoPointDto location;

    private List<Float> embedding;

    private String tenantId;

    private List<SkuItemDto> skus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkuItemDto {
        private String skuId;
        private String color;
        private BigDecimal price;
        private Integer stock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPointDto {
        private Double lat;
        private Double lon;
    }
}
