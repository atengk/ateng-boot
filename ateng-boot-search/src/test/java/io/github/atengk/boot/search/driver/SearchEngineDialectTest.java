package io.github.atengk.boot.search.driver;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import io.github.atengk.boot.search.enums.SearchEngineType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SearchEngineDialect 单元测试
 *
 * @author Ateng
 * @since 2026-11-20
 */
class SearchEngineDialectTest {

    private static final JacksonJsonpMapper JSONP_MAPPER = new JacksonJsonpMapper();

    @Test
    @DisplayName("Elasticsearch 模式下保持基础查询原样透传")
    void testElasticsearchKnnPassThrough() {
        Query baseQuery = Query.of(q -> q.term(t -> t.field("status").value("ON_SALE")));
        KnnSearch knn = KnnSearch.of(k -> k.field("embedding").queryVector(List.of(0.1f, 0.2f)).k(5L).numCandidates(20L));

        Query adapted = SearchEngineDialect.adaptKnnQuery(SearchEngineType.ELASTICSEARCH, baseQuery, List.of(knn));
        assertThat(adapted).isSameAs(baseQuery);
        assertThat(SearchEngineDialect.isOpenSearch(SearchEngineType.OPENSEARCH)).isTrue();
        assertThat(SearchEngineDialect.isOpenSearch(SearchEngineType.ELASTICSEARCH)).isFalse();
    }

    @Test
    @DisplayName("OpenSearch 模式下将 top-level KnnSearch（含多 filter）转为 Query DSL knn 子句")
    void testOpenSearchKnnDialectAdaptation() {
        Query baseQuery = Query.of(q -> q.matchAll(m -> m));
        Query filter1 = Query.of(q -> q.term(t -> t.field("category").value("phone")));
        Query filter2 = Query.of(q -> q.term(t -> t.field("brand").value("Apple")));
        KnnSearch knn = KnnSearch.of(k -> k
                .field("embedding")
                .queryVector(List.of(0.25f, 0.75f))
                .k(8L)
                .numCandidates(40L)
                .filter(List.of(filter1, filter2))
        );

        Query adapted = SearchEngineDialect.adaptKnnQuery(SearchEngineType.OPENSEARCH, baseQuery, List.of(knn));
        assertThat(adapted).isNotNull().isNotSameAs(baseQuery);

        StringBuilder sb = new StringBuilder();
        JsonpUtils.toString(adapted, JSONP_MAPPER, sb);
        String dsl = sb.toString();
        assertThat(dsl).contains("\"knn\"");
        assertThat(dsl).contains("\"embedding\"");
        assertThat(dsl).contains("\"k\":8");
        assertThat(dsl).contains("\"filter\"");
        assertThat(dsl).contains("\"category\"");
        assertThat(dsl).contains("\"brand\"");
    }

    @Test
    @DisplayName("OpenSearch 模式下遇到空白 knn.field 立即 Fail-Fast")
    void testOpenSearchKnnBlankFieldRejected() {
        KnnSearch invalidKnn = KnnSearch.of(k -> k.field(" ").queryVector(List.of(0.1f)).k(3L).numCandidates(10L));
        assertThatThrownBy(() -> SearchEngineDialect.adaptKnnQuery(SearchEngineType.OPENSEARCH, null, List.of(invalidKnn)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field");
    }
}
