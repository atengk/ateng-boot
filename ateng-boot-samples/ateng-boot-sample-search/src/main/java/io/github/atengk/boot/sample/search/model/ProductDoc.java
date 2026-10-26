package io.github.atengk.boot.sample.search.model;

import io.github.atengk.boot.search.annotation.HighlightField;
import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import io.github.atengk.boot.search.annotation.SearchRouting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 电商商品全文检索文档实体
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex(name = "product_index", shards = 3, replicas = 1)
public class ProductDoc {

    @SearchId
    private String id;

    @HighlightField
    private String title;

    @HighlightField
    private String description;

    private String category;

    @SearchRouting
    private String brand;

    private List<String> tags;

    private BigDecimal price;

    private Integer stock;

    private String status;

    private GeoPoint location;

    private List<Float> embedding;

    private String tenantId;

    private LocalDateTime createTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPoint {
        private Double lat;
        private Double lon;
    }
}
