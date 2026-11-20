package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 强类型检索分桶结果模型（与底层 Elasticsearch 客户端完全解耦）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchBucket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 桶键标准字符串表示
     */
    private String key;

    /**
     * 桶键格式化字符串（例如日期直方图的格式化时间字符串）
     */
    private String keyAsString;

    /**
     * 当前桶命中的文档数量
     */
    private long docCount;

    /**
     * 嵌套指标子聚合集合（min/max/avg/sum/cardinality/value_count 等）
     */
    @Builder.Default
    private Map<String, Double> metrics = Collections.emptyMap();

    /**
     * 嵌套多分桶子聚合集合（terms/date_histogram 等）
     */
    @Builder.Default
    private Map<String, List<SearchBucket>> bucketLists = Collections.emptyMap();

    /**
     * 嵌套过滤单桶子聚合集合（filter）
     */
    @Builder.Default
    private Map<String, SearchBucket> filterBuckets = Collections.emptyMap();

    /**
     * 嵌套数值统计子聚合集合（stats）
     */
    @Builder.Default
    private Map<String, SearchNumericStats> stats = Collections.emptyMap();

    /**
     * 嵌套分位数子聚合集合（percentiles）
     */
    @Builder.Default
    private Map<String, SearchPercentilesResult> percentiles = Collections.emptyMap();

    /**
     * 获取嵌套指标子聚合字典（保底返回空 Map）
     *
     * @return 指标子聚合字典
     */
    public Map<String, Double> getMetrics() {
        return metrics != null ? metrics : Collections.emptyMap();
    }

    /**
     * 获取嵌套多分桶子聚合字典（保底返回空 Map）
     *
     * @return 多分桶子聚合字典
     */
    public Map<String, List<SearchBucket>> getBucketLists() {
        return bucketLists != null ? bucketLists : Collections.emptyMap();
    }

    /**
     * 获取嵌套过滤单桶子聚合字典（保底返回空 Map）
     *
     * @return 过滤单桶子聚合字典
     */
    public Map<String, SearchBucket> getFilterBuckets() {
        return filterBuckets != null ? filterBuckets : Collections.emptyMap();
    }

    /**
     * 获取嵌套数值统计子聚合字典（保底返回空 Map）
     *
     * @return 数值统计子聚合字典
     */
    public Map<String, SearchNumericStats> getStats() {
        return stats != null ? stats : Collections.emptyMap();
    }

    /**
     * 获取嵌套分位数子聚合字典（保底返回空 Map）
     *
     * @return 分位数子聚合字典
     */
    public Map<String, SearchPercentilesResult> getPercentiles() {
        return percentiles != null ? percentiles : Collections.emptyMap();
    }

    /**
     * 获取当前桶下指定指标子聚合的数值
     *
     * @param name 子聚合名称
     * @return 指标数值（不存在或非有限值时返回 null）
     */
    public Double getMetricValue(String name) {
        if (name == null || metrics == null) {
            return null;
        }
        return metrics.get(name);
    }

    /**
     * 获取当前桶下指定词项分桶子聚合列表
     *
     * @param name 子聚合名称
     * @return 分桶列表（保底返回空集合）
     */
    public List<SearchBucket> getTermsBuckets(String name) {
        if (name == null || bucketLists == null) {
            return Collections.emptyList();
        }
        List<SearchBucket> list = bucketLists.get(name);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 获取当前桶下指定日期直方图分桶子聚合列表
     *
     * @param name 子聚合名称
     * @return 分桶列表（保底返回空集合）
     */
    public List<SearchBucket> getDateHistogramBuckets(String name) {
        return getTermsBuckets(name);
    }

    /**
     * 获取当前桶下指定过滤单桶子聚合
     *
     * @param name 子聚合名称
     * @return 过滤单桶结果（不存在时返回 null）
     */
    public SearchBucket getFilterBucket(String name) {
        if (name == null || filterBuckets == null) {
            return null;
        }
        return filterBuckets.get(name);
    }

    /**
     * 获取当前桶下指定数值统计子聚合
     *
     * @param name 子聚合名称
     * @return 数值统计结果（不存在时返回 null）
     */
    public SearchNumericStats getStats(String name) {
        if (name == null || stats == null) {
            return null;
        }
        return stats.get(name);
    }

    /**
     * 获取当前桶下指定分位数子聚合
     *
     * @param name 子聚合名称
     * @return 分位数结果（不存在时返回 null）
     */
    public SearchPercentilesResult getPercentiles(String name) {
        if (name == null || percentiles == null) {
            return null;
        }
        return percentiles.get(name);
    }
}
