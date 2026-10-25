package io.github.atengk.boot.search.query;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Highlight;
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

    SearchQuery(List<String> indices, Query query, List<SortOptions> sorts, Highlight highlight,
                Map<String, Aggregation> aggregations, List<KnnSearch> knnSearches,
                int from, int size, int pageNum, String routing,
                List<String> searchAfter, boolean trackTotalHits) {
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
