package io.github.atengk.boot.search.driver;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonpSerializable;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.search.enums.SearchEngineType;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索引擎协议与 DSL 方言适配器
 * <p>
 * 集中收敛 Elasticsearch 8.x 与 OpenSearch 在 REST 协议及查询 DSL 上的差异转换，
 * 保持上层 {@code SearchTemplate} 门面统一简洁。
 *
 * @author Ateng
 * @since 2026-11-20
 */
public final class SearchEngineDialect {

    private SearchEngineDialect() {
    }

    /**
     * 判断当前引擎类型是否为 OpenSearch
     *
     * @param engineType 引擎类型枚举
     * @return true 表示为 OpenSearch
     */
    public static boolean isOpenSearch(SearchEngineType engineType) {
        return engineType == SearchEngineType.OPENSEARCH;
    }

    /**
     * 在 OpenSearch 引擎模式下将顶层 {@link KnnSearch} 列表转换为 Query DSL 原生 {@code knn} 子句并融合主查询
     *
     * @param engineType  当前引擎类型
     * @param baseQuery   已有主查询（可为 null）
     * @param knnSearches kNN 检索列表
     * @return 融合后的 Query；若为 Elasticsearch 模式则原样返回 baseQuery
     */
    public static Query adaptKnnQuery(SearchEngineType engineType, Query baseQuery, List<KnnSearch> knnSearches) {
        if (!isOpenSearch(engineType) || CollectionUtil.isEmpty(knnSearches)) {
            return baseQuery;
        }
        List<Query> osKnnQueries = new ArrayList<>(knnSearches.size());
        for (KnnSearch knn : knnSearches) {
            String field = knn.field();
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("KnnSearch field must not be blank");
            }
            List<Float> vector = knn.queryVector();
            long k = knn.k();
            Query filterQuery = null;
            if (CollectionUtil.isNotEmpty(knn.filter())) {
                filterQuery = knn.filter().size() == 1
                        ? knn.filter().get(0)
                        : Query.of(q -> q.bool(b -> b.filter(knn.filter())));
            }
            final Query resolvedFilter = filterQuery;
            JsonpSerializable knnPayload = (generator, mapper) -> {
                generator.writeStartObject();
                generator.writeStartObject(field);
                generator.writeKey("vector");
                generator.writeStartArray();
                if (vector != null) {
                    for (Float v : vector) {
                        if (v != null) {
                            generator.write(v);
                        }
                    }
                }
                generator.writeEnd();
                generator.write("k", k);
                if (resolvedFilter != null) {
                    generator.writeKey("filter");
                    resolvedFilter.serialize(generator, mapper);
                }
                generator.writeEnd();
                generator.writeEnd();
            };
            osKnnQueries.add(Query.of(q -> q._custom("knn", knnPayload)));
        }
        if (baseQuery == null) {
            return osKnnQueries.size() == 1
                    ? osKnnQueries.get(0)
                    : Query.of(q -> q.bool(b -> b.must(osKnnQueries)));
        }
        return Query.of(q -> q.bool(b -> b.must(baseQuery).must(osKnnQueries)));
    }
}
