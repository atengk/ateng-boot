package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 审计日志多维分析大盘响应 DTO (Cardinality + DateHistogram + 子聚合)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogDashboardResponse {

    /**
     * 独立访问客户端 IP 去重总数 (Cardinality)
     */
    private Long uniqueIpCount;

    /**
     * 按日分桶及每日独立 IP 统计趋势列表
     */
    @Builder.Default
    private List<DailyBucketItem> dailyBuckets = Collections.emptyList();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyBucketItem {
        private String bucket;
        private long docCount;
        private Long uniqueIpCount;
    }
}
