package io.github.atengk.boot.search.driver;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.json.JsonpSerializable;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.model.SearchPointInTime;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.util.SearchExceptionClassifier;
import io.github.atengk.boot.search.util.SearchTenantHelper;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索引擎协议与 DSL 方言适配器
 * <p>
 * 集中收敛 Elasticsearch 8.x 与 OpenSearch 在 REST 协议及查询 DSL 上的差异转换，
 * 保持上层 {@code SearchTemplate} 门面统一简洁。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Slf4j
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

    /**
     * 将 {@link Duration} 格式化为搜索引擎可识别的 {@code keep_alive} 时间表达式（如 {@code 60s} / {@code 2m} / {@code 500ms}）
     *
     * @param keepAlive 正数时长
     * @return 时间表达字符串
     */
    public static String formatKeepAlive(Duration keepAlive) {
        if (keepAlive == null || keepAlive.isZero() || keepAlive.isNegative()) {
            throw new IllegalArgumentException("keepAlive must be positive");
        }
        long millis = keepAlive.toMillis();
        if (millis <= 0L) {
            return "1ms";
        }
        if (millis % 60_000L == 0L) {
            return (millis / 60_000L) + "m";
        }
        if (millis % 1_000L == 0L) {
            return (millis / 1_000L) + "s";
        }
        return millis + "ms";
    }

    /**
     * 按当前引擎方言开启 Point-In-Time (PIT) 快照上下文
     *
     * @param client    搜索引擎客户端
     * @param index     目标索引名
     * @param keepAlive 快照存活时长
     * @return 不可变 {@link SearchPointInTime} 句柄
     * @throws IOException 网络与协议 I/O 异常
     */
    public static SearchPointInTime openPointInTime(SearchEngineClient client,
                                                    String index,
                                                    Duration keepAlive) throws IOException {
        String keepAliveStr = formatKeepAlive(keepAlive);
        if (isOpenSearch(client.getEngineType())) {
            RestClient restClient = resolveRestClient(client);
            String normalizedIndex = index.startsWith("/") ? index.substring(1) : index;
            Request request = new Request("POST", "/" + normalizedIndex + "/_search/point_in_time");
            request.addParameter("keep_alive", keepAliveStr);
            Response response = restClient.performRequest(request);
            if (response.getEntity() == null) {
                throw new IllegalStateException("OpenSearch _search/point_in_time returned empty response entity");
            }
            ObjectMapper mapper = SearchTenantHelper.resolveObjectMapper(client);
            try (InputStream in = response.getEntity().getContent()) {
                JsonNode root = mapper.readTree(in);
                String pitId = root.hasNonNull("pit_id") ? root.get("pit_id").asText()
                        : (root.hasNonNull("id") ? root.get("id").asText() : null);
                if (!StringUtils.hasText(pitId)) {
                    throw new IllegalStateException("OpenSearch _search/point_in_time response missing pit_id: " + root);
                }
                return new SearchPointInTime(pitId, index, keepAlive);
            }
        }
        ElasticsearchClient esClient = client.getElasticsearchClient();
        OpenPointInTimeResponse response = esClient.openPointInTime(b -> b
                .index(index)
                .keepAlive(t -> t.time(keepAliveStr))
        );
        return new SearchPointInTime(response.id(), index, keepAlive);
    }

    /**
     * 按当前引擎方言关闭 Point-In-Time (PIT) 快照上下文
     *
     * @param client 搜索引擎客户端
     * @param pitId  PIT 快照唯一标识
     * @return true 表示成功释放；若 PIT 已不存在或过期返回 false
     * @throws IOException 网络与协议 I/O 异常
     */
    public static boolean closePointInTime(SearchEngineClient client, String pitId) throws IOException {
        try {
            if (isOpenSearch(client.getEngineType())) {
                RestClient restClient = resolveRestClient(client);
                ObjectMapper mapper = SearchTenantHelper.resolveObjectMapper(client);
                Request request = new Request("DELETE", "/_search/point_in_time");
                request.setJsonEntity(mapper.writeValueAsString(Map.of("pit_id", List.of(pitId))));
                Response response = restClient.performRequest(request);
                if (response.getEntity() == null) {
                    return true;
                }
                try (InputStream in = response.getEntity().getContent()) {
                    JsonNode root = mapper.readTree(in);
                    if (root.has("succeeded")) {
                        return root.get("succeeded").asBoolean(false);
                    }
                    if (root.has("pits") && root.get("pits").isArray() && !root.get("pits").isEmpty()) {
                        return root.get("pits").get(0).path("successful").asBoolean(true);
                    }
                    return true;
                }
            }
            ClosePointInTimeResponse response = client.getElasticsearchClient().closePointInTime(b -> b.id(pitId));
            return response.succeeded();
        } catch (Exception e) {
            if (SearchExceptionClassifier.isNotFound(e)) {
                return false;
            }
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("Failed to close PIT [" + pitId + "]", e);
        }
    }

    private static RestClient resolveRestClient(SearchEngineClient client) {
        try {
            RestClient restClient = client.getNativeClient(RestClient.class);
            if (restClient != null) {
                return restClient;
            }
        } catch (IllegalArgumentException e) {
            log.debug("getNativeClient(RestClient.class) unsupported, falling back to RestClientTransport: {}", e.getMessage());
        }
        ElasticsearchClient esClient = client.getElasticsearchClient();
        if (esClient != null && esClient._transport() instanceof RestClientTransport restTransport) {
            return restTransport.restClient();
        }
        throw new IllegalStateException("RestClient is unavailable on SearchEngineClient for OpenSearch PIT request");
    }
}
