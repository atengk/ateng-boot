package io.github.atengk.boot.search.model;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import io.github.atengk.boot.core.model.PageResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 搜索引擎标准浅分页结果载荷模型
 * <p>
 * 封装分页核心要素与命中文档列表，支持无缝流式转为 Core 模块的 {@link PageResult}。
 *
 * @param <T> 文档实体泛型
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchPage<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中文档载荷列表
     */
    @Builder.Default
    private List<SearchHit<T>> hits = Collections.emptyList();

    /**
     * 总命中记录数
     */
    private long total;

    /**
     * 当前页码（从 1 开始）
     */
    private int pageNum;

    /**
     * 每页大小
     */
    private int pageSize;

    /**
     * 聚合度量结果集合（Key 为聚合名称，Value 为聚合载荷 Aggregate）
     */
    @Builder.Default
    private Map<String, Aggregate> aggregations = Collections.emptyMap();

    /**
     * 检索建议/自动补全结果集合（Key 为 suggester 名称，Value 为建议候选列表）
     */
    @Builder.Default
    private Map<String, List<SearchSuggestResult>> suggestions = Collections.emptyMap();

    /**
     * 读取强类型指标聚合数值（min / max / avg / sum / cardinality / value_count 等）
     *
     * @param name 聚合名称
     * @return 聚合指标数值（不存在或非有限值时返回 null）
     */
    public Double getMetricValue(String name) {
        return SearchAggregationConverter.getMetricValue(this.aggregations, name);
    }

    /**
     * 读取强类型词项分桶聚合结果列表
     *
     * @param name 聚合名称
     * @return 强类型分桶列表（保底返回空集合）
     */
    public List<SearchBucket> getTermsBuckets(String name) {
        return SearchAggregationConverter.getTermsBuckets(this.aggregations, name);
    }

    /**
     * 读取强类型日期直方图分桶聚合结果列表
     *
     * @param name 聚合名称
     * @return 强类型分桶列表（保底返回空集合）
     */
    public List<SearchBucket> getDateHistogramBuckets(String name) {
        return SearchAggregationConverter.getDateHistogramBuckets(this.aggregations, name);
    }

    /**
     * 读取强类型过滤单桶聚合结果
     *
     * @param name 聚合名称
     * @return 过滤单桶模型（不存在时返回 null）
     */
    public SearchBucket getFilterBucket(String name) {
        return SearchAggregationConverter.getFilterBucket(this.aggregations, name);
    }

    /**
     * 读取强类型数值统计聚合结果 (count / min / max / avg / sum)
     *
     * @param name 聚合名称
     * @return 数值统计模型（不存在时返回 null）
     */
    public SearchNumericStats getStats(String name) {
        return SearchAggregationConverter.getStats(this.aggregations, name);
    }

    /**
     * 读取强类型分位数聚合结果 (P50 / P95 / P99 等)
     *
     * @param name 聚合名称
     * @return 分位数模型（不存在时返回 null）
     */
    public SearchPercentilesResult getPercentiles(String name) {
        return SearchAggregationConverter.getPercentiles(this.aggregations, name);
    }

    /**
     * 读取指定 Suggester 的推荐候选项列表
     *
     * @param name Suggester 名称
     * @return 建议结果列表（保底返回空集合）
     */
    public List<SearchSuggestResult> getSuggestions(String name) {
        if (name == null || suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchSuggestResult> list = suggestions.get(name);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 获取命中总记录数（等价于 getTotal）
     *
     * @return 总命中数
     */
    public long getTotalHits() {
        return this.total;
    }

    /**
     * 提取纯文档实体列表
     *
     * @return 文档实体列表
     */
    public List<T> getDataList() {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.stream()
                .map(SearchHit::getContent)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 计算总页数
     *
     * @return 总页数
     */
    public long getTotalPages() {
        if (pageSize <= 0) {
            return 0L;
        }
        return (total + pageSize - 1) / pageSize;
    }

    /**
     * 转为 Core 统一标准分页响应模型（内容为实体对象本体）
     *
     * @return PageResult 实例
     */
    public PageResult<T> toPageResult() {
        return PageResult.of(getDataList(), total, pageNum, pageSize, getTotalPages());
    }

    /**
     * 转为 Core 统一标准分页响应模型（内容保留 SearchHit 完整载荷，包含高亮、打分与版本元数据）
     *
     * @return PageResult 实例
     */
    public PageResult<SearchHit<T>> toHitPageResult() {
        return PageResult.of(hits != null ? hits : Collections.emptyList(), total, pageNum, pageSize, getTotalPages());
    }

    /**
     * 将当前分页结果安全投影转换为目标视图对象分页结果，自动回填 VO 上的 {@code @HighlightField}
     *
     * @param mapper 实体到视图对象的转换函数
     * @param <V>    目标视图对象泛型
     * @return 转换后的 {@link SearchPage}
     */
    public <V> SearchPage<V> map(Function<T, V> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        List<SearchHit<V>> mappedHits = (this.hits == null || this.hits.isEmpty())
                ? Collections.emptyList()
                : this.hits.stream().map(hit -> hit.map(mapper)).toList();
        return SearchPage.<V>builder()
                .hits(mappedHits)
                .total(this.total)
                .pageNum(this.pageNum)
                .pageSize(this.pageSize)
                .aggregations(this.aggregations != null ? this.aggregations : Collections.emptyMap())
                .suggestions(this.suggestions != null ? this.suggestions : Collections.emptyMap())
                .build();
    }

    /**
     * 构造空分页对象
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param <T>      实体类型
     * @return 空分页对象
     */
    public static <T> SearchPage<T> empty(int pageNum, int pageSize) {
        return SearchPage.<T>builder()
                .hits(Collections.emptyList())
                .total(0L)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }
}
