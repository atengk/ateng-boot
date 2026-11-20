package io.github.atengk.boot.search.query;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.FieldCollapse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import lombok.Getter;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 搜索引擎只读不可变查询规格对象
 * <p>
 * 封装由 {@link SearchQueryBuilder} 流式组装完成的 DSL 查询条件、排序规则、高亮配置、聚合算子及分页参数。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class SearchQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标索引名列表（若为空则自动由实体类注解推导）
     */
    private final List<String> indices;

    /**
     * DSL 核心查询体
     */
    private final Query query;

    /**
     * 排序规则列表
     */
    private final List<SortOptions> sorts;

    /**
     * 高亮渲染配置
     */
    private final Highlight highlight;

    /**
     * 聚合计算规则集合
     */
    private final Map<String, Aggregation> aggregations;

    /**
     * AI 向量相似度检索列表
     */
    private final List<KnnSearch> knnSearches;

    /**
     * 浅分页偏移量 (from)
     */
    private final int from;

    /**
     * 每页查询记录数 (size)
     */
    private final int size;

    /**
     * 浅分页业务页码（从 1 开始）
     */
    private final int pageNum;

    /**
     * 分片路由键
     */
    private final String routing;

    /**
     * 深度游标分页排序凭据值列表 (SearchAfter)
     */
    private final List<String> searchAfter;

    /**
     * 是否精确追踪总匹配记录数（默认 true，确保深浅分页均获取精准 total）
     */
    private final boolean trackTotalHits;

    /**
     * 字段折叠去重配置 (Field Collapsing)
     */
    private final FieldCollapse collapse;

    /**
     * _source 投影保留字段列表
     */
    private final List<String> sourceIncludes;

    /**
     * _source 投影剔除字段列表
     */
    private final List<String> sourceExcludes;

    /**
     * 是否拉取 _source 原文（null 表示使用引擎默认行为）
     */
    private final Boolean fetchSource;

    SearchQuery(List<String> indices, Query query, List<SortOptions> sorts, Highlight highlight,
                Map<String, Aggregation> aggregations, List<KnnSearch> knnSearches,
                int from, int size, int pageNum, String routing,
                List<String> searchAfter, boolean trackTotalHits) {
        this(indices, query, sorts, highlight, aggregations, knnSearches, from, size, pageNum, routing,
                searchAfter, trackTotalHits, null, null, null, null);
    }

    SearchQuery(List<String> indices, Query query, List<SortOptions> sorts, Highlight highlight,
                Map<String, Aggregation> aggregations, List<KnnSearch> knnSearches,
                int from, int size, int pageNum, String routing,
                List<String> searchAfter, boolean trackTotalHits,
                FieldCollapse collapse) {
        this(indices, query, sorts, highlight, aggregations, knnSearches, from, size, pageNum, routing,
                searchAfter, trackTotalHits, collapse, null, null, null);
    }

    SearchQuery(List<String> indices, Query query, List<SortOptions> sorts, Highlight highlight,
                Map<String, Aggregation> aggregations, List<KnnSearch> knnSearches,
                int from, int size, int pageNum, String routing,
                List<String> searchAfter, boolean trackTotalHits,
                FieldCollapse collapse, List<String> sourceIncludes,
                List<String> sourceExcludes, Boolean fetchSource) {
        this.indices = indices != null ? Collections.unmodifiableList(indices) : Collections.emptyList();
        this.query = query;
        this.sorts = sorts != null ? Collections.unmodifiableList(sorts) : Collections.emptyList();
        this.highlight = highlight;
        this.aggregations = aggregations != null ? Collections.unmodifiableMap(aggregations) : Collections.emptyMap();
        this.knnSearches = knnSearches != null ? Collections.unmodifiableList(knnSearches) : Collections.emptyList();
        this.from = from;
        this.size = size;
        this.pageNum = pageNum;
        this.routing = routing;
        this.searchAfter = searchAfter != null ? Collections.unmodifiableList(searchAfter) : Collections.emptyList();
        this.trackTotalHits = trackTotalHits;
        this.collapse = collapse;
        this.sourceIncludes = sourceIncludes != null ? Collections.unmodifiableList(sourceIncludes) : Collections.emptyList();
        this.sourceExcludes = sourceExcludes != null ? Collections.unmodifiableList(sourceExcludes) : Collections.emptyList();
        this.fetchSource = fetchSource;
    }

    /**
     * 构造底层 Elasticsearch/OpenSearch {@link SourceConfig} 投影配置（无配置时返回 {@code null}）
     *
     * @return SourceConfig 实例或 null
     */
    public SourceConfig toSourceConfig() {
        if (Boolean.FALSE.equals(fetchSource)) {
            return SourceConfig.of(s -> s.fetch(false));
        }
        boolean hasIncludes = sourceIncludes != null && !sourceIncludes.isEmpty();
        boolean hasExcludes = sourceExcludes != null && !sourceExcludes.isEmpty();
        if (hasIncludes || hasExcludes) {
            return SourceConfig.of(s -> s.filter(f -> {
                if (hasIncludes) {
                    f.includes(sourceIncludes);
                }
                if (hasExcludes) {
                    f.excludes(sourceExcludes);
                }
                return f;
            }));
        }
        if (Boolean.TRUE.equals(fetchSource)) {
            return SourceConfig.of(s -> s.fetch(true));
        }
        return null;
    }

    /**
     * 派生分支查询副本（保留索引、路由、_source 裁剪及跟踪总数配置，替换核心查询体、kNN 与分页窗口）
     *
     * @param overrideQuery                 新核心查询体
     * @param overrideKnn                   新 kNN 列表
     * @param overrideFrom                  新偏移量
     * @param overrideSize                  新返回条数
     * @param keepHighlightAndAggregations  是否保留高亮与聚合配置
     * @return 不可变 SearchQuery 副本
     */
    public SearchQuery deriveBranch(Query overrideQuery,
                                    List<KnnSearch> overrideKnn,
                                    int overrideFrom,
                                    int overrideSize,
                                    boolean keepHighlightAndAggregations) {
        int resolvedPageNum = overrideSize > 0 ? (overrideFrom / overrideSize) + 1 : 1;
        return new SearchQuery(
                this.indices,
                overrideQuery,
                Collections.emptyList(),
                keepHighlightAndAggregations ? this.highlight : null,
                keepHighlightAndAggregations ? this.aggregations : Collections.emptyMap(),
                overrideKnn,
                overrideFrom,
                overrideSize,
                resolvedPageNum,
                this.routing,
                Collections.emptyList(),
                this.trackTotalHits,
                null,
                this.sourceIncludes,
                this.sourceExcludes,
                this.fetchSource
        );
    }

    /**
     * 创建一个全新的链式构建器
     *
     * @return SearchQueryBuilder 构建器实例
     */
    public static SearchQueryBuilder builder() {
        return new SearchQueryBuilder();
    }
}
