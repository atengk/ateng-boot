package io.github.atengk.boot.sample.search.dto;

import io.github.atengk.boot.search.model.SearchNumericStats;
import io.github.atengk.boot.search.model.SearchPercentilesResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 商品价格统计与分位数多维分析大盘响应 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDashboardResponse {

    /**
     * 独立品牌去重总数
     */
    private Long uniqueBrandCount;

    /**
     * 全局价格数值统计 (count / min / max / avg / sum)
     */
    private SearchNumericStats priceStats;

    /**
     * 全局价格分位数 (P50 / P95 / P99)
     */
    private SearchPercentilesResult pricePercentiles;

    /**
     * 按类目分组统计及其嵌套子聚合集合
     */
    @Builder.Default
    private List<CategoryStatsItem> categoryStats = Collections.emptyList();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryStatsItem {
        private String category;
        private long docCount;
        private SearchNumericStats priceStats;
        private SearchPercentilesResult pricePercentiles;
    }
}
