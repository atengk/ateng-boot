package io.github.atengk.boot.search.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.json.JsonData;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 链式流式搜索查询构建器
 * <p>
 * 提供高直觉 Fluent API，封装精准匹配、分词检索、范围通配、复合布尔、空间距离、AI 向量检索、高亮与聚合算子，
 * 支持官方原生 Query 逃生舱并自动生成不可变的 {@link SearchQuery}。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class SearchQueryBuilder {

    /**
     * 创建全新查询构造器实例
     *
     * @return 新的 SearchQueryBuilder 实例
     */
    public static SearchQueryBuilder create() {
        return new SearchQueryBuilder();
    }

    private final List<Query> mustQueries = new ArrayList<>();
    private final List<Query> filterQueries = new ArrayList<>();
    private final List<Query> shouldQueries = new ArrayList<>();
    private final List<Query> mustNotQueries = new ArrayList<>();
    private String minimumShouldMatch;

    private final List<SortOptions> sorts = new ArrayList<>();
    private final Map<String, Aggregation> aggregations = new LinkedHashMap<>();
    private final List<KnnSearch> knnSearches = new ArrayList<>();
    private final Set<String> highlightFields = new LinkedHashSet<>();
    private String highlightPreTag = "<em>";
    private String highlightPostTag = "</em>";

    private int from = 0;
    private int size = 10;
    private int pageNum = 1;
    private String routing;
    private final List<String> searchAfter = new ArrayList<>();
    private final List<String> indices = new ArrayList<>();
    private boolean trackTotalHits = true;

    // ==================== 1. 精确与分词匹配 ====================

    /**
     * Term 精确匹配（自动织入 filter 分支以享受查询缓存）
     *
     * @param field 字段名
     * @param value 匹配值
     * @return 当前构建器
     */
    public SearchQueryBuilder term(String field, Object value) {
        if (!StringUtils.hasText(field) || value == null) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.term(t -> t.field(field).value(toFieldValue(value)))));
        return this;
    }

    /**
     * Terms 多值精确匹配（自动织入 filter 分支）
     *
     * @param field  字段名
     * @param values 候选值集合
     * @return 当前构建器
     */
    public SearchQueryBuilder terms(String field, Collection<?> values) {
        if (!StringUtils.hasText(field) || values == null || values.isEmpty()) {
            return this;
        }
        List<FieldValue> fieldValues = values.stream()
                .filter(Objects::nonNull)
                .map(SearchQueryBuilder::toFieldValue)
                .toList();
        if (fieldValues.isEmpty()) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.terms(t -> t.field(field).terms(tf -> tf.value(fieldValues)))));
        return this;
    }

    /**
     * Match 分词全文检索（织入 must 分支以参与相关度打分）
     *
     * @param field 字段名
     * @param query 查询关键词
     * @return 当前构建器
     */
    public SearchQueryBuilder match(String field, Object query) {
        if (!StringUtils.hasText(field) || query == null) {
            return this;
        }
        mustQueries.add(Query.of(q -> q.match(m -> m.field(field).query(toFieldValue(query)))));
        return this;
    }

    /**
     * MultiMatch 多字段分词检索（织入 must 分支）
     *
     * @param query  查询关键词
     * @param fields 匹配字段名数组
     * @return 当前构建器
     */
    public SearchQueryBuilder multiMatch(Object query, String... fields) {
        if (query == null || fields == null || fields.length == 0) {
            return this;
        }
        mustQueries.add(Query.of(q -> q.multiMatch(mm -> mm.query(query.toString()).fields(Arrays.asList(fields)))));
        return this;
    }

    // ==================== 2. 区间与通配 ====================

    /**
     * 区间查询 [from, to]（自动织入 filter 分支）
     *
     * @param field 字段名
     * @param from  起始值（可为 null）
     * @param to    截止值（可为 null）
     * @return 当前构建器
     */
    public SearchQueryBuilder range(String field, Object from, Object to) {
        return range(field, null, from, null, to);
    }

    /**
     * 精细化四端区间查询
     *
     * @param field 字段名
     * @param gt    大于（可为 null）
     * @param gte   大于等于（可为 null）
     * @param lt    小于（可为 null）
     * @param lte   小于等于（可为 null）
     * @return 当前构建器
     */
    public SearchQueryBuilder range(String field, Object gt, Object gte, Object lt, Object lte) {
        if (!StringUtils.hasText(field) || (gt == null && gte == null && lt == null && lte == null)) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.range(r -> {
            r.field(field);
            if (gt != null) r.gt(JsonData.of(gt));
            if (gte != null) r.gte(JsonData.of(gte));
            if (lt != null) r.lt(JsonData.of(lt));
            if (lte != null) r.lte(JsonData.of(lte));
            return r;
        })));
        return this;
    }

    public SearchQueryBuilder gt(String field, Object value) {
        return range(field, value, null, null, null);
    }

    public SearchQueryBuilder gte(String field, Object value) {
        return range(field, null, value, null, null);
    }

    public SearchQueryBuilder lt(String field, Object value) {
        return range(field, null, null, value, null);
    }

    public SearchQueryBuilder lte(String field, Object value) {
        return range(field, null, null, null, value);
    }

    /**
     * Wildcard 通配符查询（织入 filter 分支）
     *
     * @param field 字段名
     * @param value 通配表达式（如 *abc*）
     * @return 当前构建器
     */
    public SearchQueryBuilder wildcard(String field, String value) {
        if (!StringUtils.hasText(field) || !StringUtils.hasText(value)) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.wildcard(w -> w.field(field).value(value))));
        return this;
    }

    /**
     * Prefix 前缀查询（织入 filter 分支）
     *
     * @param field  字段名
     * @param prefix 前缀字符串
     * @return 当前构建器
     */
    public SearchQueryBuilder prefix(String field, String prefix) {
        if (!StringUtils.hasText(field) || !StringUtils.hasText(prefix)) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.prefix(p -> p.field(field).value(prefix))));
        return this;
    }

    /**
     * Exists 字段非空存在性查询（织入 filter 分支）
     *
     * @param field 字段名
     * @return 当前构建器
     */
    public SearchQueryBuilder exists(String field) {
        if (!StringUtils.hasText(field)) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.exists(e -> e.field(field))));
        return this;
    }

    // ==================== 3. 复合布尔组合 ====================

    /**
     * 显式追加 must 子条件
     *
     * @param query ES 原生 Query
     * @return 当前构建器
     */
    public SearchQueryBuilder must(Query query) {
        if (query != null) {
            mustQueries.add(query);
        }
        return this;
    }

    /**
     * 嵌套子构建器构建 must 条件
     *
     * @param subBuilderConsumer 子构建器闭包
     * @return 当前构建器
     */
    public SearchQueryBuilder must(Consumer<SearchQueryBuilder> subBuilderConsumer) {
        if (subBuilderConsumer != null) {
            SearchQueryBuilder sub = new SearchQueryBuilder();
            subBuilderConsumer.accept(sub);
            Query subQuery = sub.buildQuery();
            if (subQuery != null) {
                mustQueries.add(subQuery);
            }
        }
        return this;
    }

    /**
     * 显式追加 filter 子条件（享受 ES Filter Cache 极速过滤与零打分开销）
     *
     * @param query ES 原生 Query
     * @return 当前构建器
     */
    public SearchQueryBuilder filter(Query query) {
        if (query != null) {
            filterQueries.add(query);
        }
        return this;
    }

    /**
     * 嵌套子构建器构建 filter 条件
     *
     * @param subBuilderConsumer 子构建器闭包
     * @return 当前构建器
     */
    public SearchQueryBuilder filter(Consumer<SearchQueryBuilder> subBuilderConsumer) {
        if (subBuilderConsumer != null) {
            SearchQueryBuilder sub = new SearchQueryBuilder();
            subBuilderConsumer.accept(sub);
            Query subQuery = sub.buildQuery();
            if (subQuery != null) {
                filterQueries.add(subQuery);
            }
        }
        return this;
    }

    /**
     * 显式追加 should 子条件
     *
     * @param query ES 原生 Query
     * @return 当前构建器
     */
    public SearchQueryBuilder should(Query query) {
        if (query != null) {
            shouldQueries.add(query);
        }
        return this;
    }

    /**
     * 嵌套子构建器构建 should 条件
     *
     * @param subBuilderConsumer 子构建器闭包
     * @return 当前构建器
     */
    public SearchQueryBuilder should(Consumer<SearchQueryBuilder> subBuilderConsumer) {
        if (subBuilderConsumer != null) {
            SearchQueryBuilder sub = new SearchQueryBuilder();
            subBuilderConsumer.accept(sub);
            Query subQuery = sub.buildQuery();
            if (subQuery != null) {
                shouldQueries.add(subQuery);
            }
        }
        return this;
    }

    /**
     * 显式追加 mustNot 否定排除条件
     *
     * @param query ES 原生 Query
     * @return 当前构建器
     */
    public SearchQueryBuilder mustNot(Query query) {
        if (query != null) {
            mustNotQueries.add(query);
        }
        return this;
    }

    /**
     * 嵌套子构建器构建 mustNot 条件
     *
     * @param subBuilderConsumer 子构建器闭包
     * @return 当前构建器
     */
    public SearchQueryBuilder mustNot(Consumer<SearchQueryBuilder> subBuilderConsumer) {
        if (subBuilderConsumer != null) {
            SearchQueryBuilder sub = new SearchQueryBuilder();
            subBuilderConsumer.accept(sub);
            Query subQuery = sub.buildQuery();
            if (subQuery != null) {
                mustNotQueries.add(subQuery);
            }
        }
        return this;
    }

    /**
     * 配置 should 匹配的最少满足分支数
     *
     * @param minimum 最少满足数（如 1）
     * @return 当前构建器
     */
    public SearchQueryBuilder minimumShouldMatch(int minimum) {
        this.minimumShouldMatch = String.valueOf(minimum);
        return this;
    }

    /**
     * 配置 should 匹配表达式（如 "50%" 或 "2"）
     *
     * @param minimum 表达式
     * @return 当前构建器
     */
    public SearchQueryBuilder minimumShouldMatch(String minimum) {
        this.minimumShouldMatch = minimum;
        return this;
    }

    // ==================== 4. 空间与向量算子 ====================

    /**
     * GeoDistance 地理距离范围筛选（自动织入 filter 分支）
     *
     * @param field    地理坐标字段名（geo_point）
     * @param lat      中心点纬度
     * @param lon      中心点经度
     * @param distance 距离范围字符串（如 "5km", "500m"）
     * @return 当前构建器
     */
    public SearchQueryBuilder geoDistance(String field, double lat, double lon, String distance) {
        if (!StringUtils.hasText(field) || !StringUtils.hasText(distance)) {
            return this;
        }
        filterQueries.add(Query.of(q -> q.geoDistance(g -> g
                .field(field)
                .distance(distance)
                .location(loc -> loc.latlon(ll -> ll.lat(lat).lon(lon)))
        )));
        return this;
    }

    /**
     * AI 稠密向量相似度检索 (kNN)
     *
     * @param field         稠密向量字段名 (dense_vector)
     * @param vector        查询向量数组
     * @param k             召回 TopK 数量
     * @param numCandidates 每个分片候选集大小 (通常 >= k)
     * @return 当前构建器
     */
    public SearchQueryBuilder knn(String field, float[] vector, int k, int numCandidates) {
        if (!StringUtils.hasText(field) || vector == null || vector.length == 0) {
            return this;
        }
        List<Float> floatList = new ArrayList<>(vector.length);
        for (float v : vector) {
            floatList.add(v);
        }
        return knn(field, floatList, k, numCandidates);
    }

    /**
     * 声明式 KNN 向量近邻检索（支持 List<Float> 集合）
     *
     * @param field         稠密向量字段名 (dense_vector)
     * @param vector        查询向量集合
     * @param k             召回 TopK 数量
     * @param numCandidates 每个分片候选集大小 (通常 >= k)
     * @return 当前构建器
     */
    public SearchQueryBuilder knn(String field, List<Float> vector, int k, int numCandidates) {
        if (!StringUtils.hasText(field) || vector == null || vector.isEmpty()) {
            return this;
        }
        knnSearches.add(KnnSearch.of(builder -> builder
                .field(field)
                .queryVector(new ArrayList<>(vector))
                .k((long) k)
                .numCandidates((long) numCandidates)
        ));
        return this;
    }

    /**
     * 全量匹配检索 (Match All)
     *
     * @return 当前构建器
     */
    public SearchQueryBuilder matchAll() {
        this.mustQueries.add(Query.of(q -> q.matchAll(m -> m)));
        return this;
    }

    // ==================== 5. 原生逃生舱 ====================

    /**
     * 官方原生高级 Query 逃生舱（直接塞入 ES 原生 Query 对象）
     *
     * @param esQuery ES 原生 Query
     * @return 当前构建器
     */
    public SearchQueryBuilder nativeQuery(Query esQuery) {
        if (esQuery != null) {
            mustQueries.add(esQuery);
        }
        return this;
    }

    // ==================== 6. 排序规则 ====================

    /**
     * 字段升序排序
     *
     * @param field 排序字段名
     * @return 当前构建器
     */
    public SearchQueryBuilder orderByAsc(String field) {
        if (StringUtils.hasText(field)) {
            sorts.add(SortOptions.of(s -> s.field(f -> f.field(field).order(SortOrder.Asc))));
        }
        return this;
    }

    /**
     * 字段降序排序
     *
     * @param field 排序字段名
     * @return 当前构建器
     */
    public SearchQueryBuilder orderByDesc(String field) {
        if (StringUtils.hasText(field)) {
            sorts.add(SortOptions.of(s -> s.field(f -> f.field(field).order(SortOrder.Desc))));
        }
        return this;
    }

    /**
     * 按匹配相关度得分打分排序 (_score desc)
     *
     * @return 当前构建器
     */
    public SearchQueryBuilder orderByScore() {
        sorts.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
        return this;
    }

    /**
     * 按地理距离升序排序（默认最近优先）
     *
     * @param field 地理坐标字段
     * @param lat   中心点纬度
     * @param lon   中心点经度
     * @return 当前构建器
     */
    public SearchQueryBuilder orderByGeoDistance(String field, double lat, double lon) {
        return orderByGeoDistance(field, lat, lon, SortOrder.Asc);
    }

    /**
     * 按地理距离排序
     *
     * @param field 地理坐标字段
     * @param lat   中心点纬度
     * @param lon   中心点经度
     * @param order 排序方向（SortOrder.Asc / Desc）
     * @return 当前构建器
     */
    public SearchQueryBuilder orderByGeoDistance(String field, double lat, double lon, SortOrder order) {
        if (StringUtils.hasText(field)) {
            sorts.add(SortOptions.of(s -> s.geoDistance(g -> g
                    .field(field)
                    .location(loc -> loc.latlon(ll -> ll.lat(lat).lon(lon)))
                    .order(order != null ? order : SortOrder.Asc)
            )));
        }
        return this;
    }

    // ==================== 7. 高亮渲染 ====================

    /**
     * 指定需要高亮匹配的字段（采用默认 <em> 和 </em> 标签）
     *
     * @param fields 高亮字段列表
     * @return 当前构建器
     */
    public SearchQueryBuilder highlight(String... fields) {
        if (fields != null) {
            for (String f : fields) {
                if (StringUtils.hasText(f)) {
                    highlightFields.add(f);
                }
            }
        }
        return this;
    }

    /**
     * 指定高亮前后缀标签与高亮字段
     *
     * @param preTag  前缀标签（如 "<span class='highlight'>"）
     * @param postTag 后缀标签（如 "</span>"）
     * @param fields  高亮字段列表
     * @return 当前构建器
     */
    public SearchQueryBuilder highlightWithTags(String preTag, String postTag, String... fields) {
        if (StringUtils.hasText(preTag)) {
            this.highlightPreTag = preTag;
        }
        if (StringUtils.hasText(postTag)) {
            this.highlightPostTag = postTag;
        }
        return highlight(fields);
    }

    // ==================== 8. 聚合算子 ====================

    /**
     * Terms 分组词条聚合快捷算子
     *
     * @param name  聚合名称
     * @param field 聚合字段名
     * @param size  返回桶大小
     * @return 当前构建器
     */
    public SearchQueryBuilder termsAggregation(String name, String field, int size) {
        if (StringUtils.hasText(name) && StringUtils.hasText(field)) {
            aggregations.put(name, Aggregation.of(a -> a.terms(t -> t.field(field).size(size))));
        }
        return this;
    }

    /**
     * Sum 数值求和聚合快捷算子
     *
     * @param name  聚合名称
     * @param field 求和数值字段名
     * @return 当前构建器
     */
    public SearchQueryBuilder sumAggregation(String name, String field) {
        if (StringUtils.hasText(name) && StringUtils.hasText(field)) {
            aggregations.put(name, Aggregation.of(a -> a.sum(s -> s.field(field))));
        }
        return this;
    }

    /**
     * Avg 平均值聚合快捷算子
     *
     * @param name  聚合名称
     * @param field 目标数值字段名
     * @return 当前构建器
     */
    public SearchQueryBuilder avgAggregation(String name, String field) {
        if (StringUtils.hasText(name) && StringUtils.hasText(field)) {
            aggregations.put(name, Aggregation.of(a -> a.avg(av -> av.field(field))));
        }
        return this;
    }

    // ==================== 9. 分页与控制 ====================

    /**
     * 分页参数设置（页码从 1 开始自动换算 from 偏移量）
     *
     * @param pageNum  页码（>= 1）
     * @param pageSize 每页大小（>= 1）
     * @return 当前构建器
     */
    public SearchQueryBuilder page(int pageNum, int pageSize) {
        this.pageNum = Math.max(1, pageNum);
        this.size = Math.max(1, pageSize);
        this.from = (this.pageNum - 1) * this.size;
        return this;
    }

    public SearchQueryBuilder from(int from) {
        this.from = Math.max(0, from);
        return this;
    }

    public SearchQueryBuilder size(int size) {
        this.size = Math.max(1, size);
        return this;
    }

    public SearchQueryBuilder routing(String routing) {
        this.routing = routing;
        return this;
    }

    public SearchQueryBuilder searchAfter(List<String> sortValues) {
        if (sortValues != null) {
            this.searchAfter.clear();
            this.searchAfter.addAll(sortValues);
        }
        return this;
    }

    public SearchQueryBuilder searchAfter(String... sortValues) {
        if (sortValues != null) {
            return searchAfter(Arrays.asList(sortValues));
        }
        return this;
    }

    public SearchQueryBuilder index(String index) {
        if (StringUtils.hasText(index)) {
            this.indices.add(index);
        }
        return this;
    }

    public SearchQueryBuilder indices(String... indices) {
        if (indices != null) {
            for (String idx : indices) {
                if (StringUtils.hasText(idx)) {
                    this.indices.add(idx);
                }
            }
        }
        return this;
    }

    public SearchQueryBuilder trackTotalHits(boolean trackTotalHits) {
        this.trackTotalHits = trackTotalHits;
        return this;
    }

    // ==================== 10. 构建执行 ====================

    /**
     * 组装生成完整的 Query 查询体
     *
     * @return Query 实例
     */
    public Query buildQuery() {
        boolean hasMust = !mustQueries.isEmpty();
        boolean hasFilter = !filterQueries.isEmpty();
        boolean hasShould = !shouldQueries.isEmpty();
        boolean hasMustNot = !mustNotQueries.isEmpty();

        if (!hasMust && !hasFilter && !hasShould && !hasMustNot) {
            return null;
        }

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        if (hasMust) {
            boolBuilder.must(mustQueries);
        }
        if (hasFilter) {
            boolBuilder.filter(filterQueries);
        }
        if (hasShould) {
            boolBuilder.should(shouldQueries);
        }
        if (hasMustNot) {
            boolBuilder.mustNot(mustNotQueries);
        }
        if (StringUtils.hasText(minimumShouldMatch)) {
            boolBuilder.minimumShouldMatch(minimumShouldMatch);
        }
        return Query.of(q -> q.bool(boolBuilder.build()));
    }

    /**
     * 构建不可变 SearchQuery 查询对象
     *
     * @return SearchQuery 实例
     */
    public SearchQuery build() {
        Query finalQuery = buildQuery();
        if (finalQuery == null) {
            // 缺省默认匹配全部
            finalQuery = Query.of(q -> q.matchAll(m -> m));
        }

        Highlight highlight = null;
        if (!highlightFields.isEmpty()) {
            Map<String, HighlightField> fieldMap = new HashMap<>();
            for (String f : highlightFields) {
                fieldMap.put(f, HighlightField.of(hf -> hf));
            }
            highlight = Highlight.of(h -> h
                    .preTags(List.of(highlightPreTag))
                    .postTags(List.of(highlightPostTag))
                    .fields(fieldMap)
            );
        }

        return new SearchQuery(
                new ArrayList<>(indices),
                finalQuery,
                new ArrayList<>(sorts),
                highlight,
                new LinkedHashMap<>(aggregations),
                new ArrayList<>(knnSearches),
                from,
                size,
                pageNum,
                routing,
                new ArrayList<>(searchAfter),
                trackTotalHits
        );
    }

    private static FieldValue toFieldValue(Object value) {
        if (value == null) {
            return FieldValue.NULL;
        }
        if (value instanceof String s) {
            return FieldValue.of(s);
        }
        if (value instanceof Long l) {
            return FieldValue.of(l);
        }
        if (value instanceof Integer i) {
            return FieldValue.of(i.longValue());
        }
        if (value instanceof Short s) {
            return FieldValue.of(s.longValue());
        }
        if (value instanceof Byte b) {
            return FieldValue.of(b.longValue());
        }
        if (value instanceof Double d) {
            return FieldValue.of(d);
        }
        if (value instanceof Float f) {
            return FieldValue.of(f.doubleValue());
        }
        if (value instanceof Boolean b) {
            return FieldValue.of(b);
        }
        return FieldValue.of(value.toString());
    }
}
