package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品信息响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    private String id;
    private String title;
    private String description;
    private String category;
    private String brand;
    private List<String> tags;
    private BigDecimal price;
    private Integer stock;
    private String status;
    private GeoPointVo location;
    private List<SkuItemVo> skus;
    private String tenantId;
    private LocalDateTime createTime;

    /**
     * 相关度得分（仅全文搜索返回）
     */
    private Double score;

    /**
     * 排序凭据值（深翻页游标）
     */
    private List<Object> sortValues;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkuItemVo {
        private String skuId;
        private String color;
        private BigDecimal price;
        private Integer stock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPointVo {
        private Double lat;
        private Double lon;
    }
}
