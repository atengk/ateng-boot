package io.github.atengk.boot.milvus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusSearchRequest;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MilvusSearchRequest 统一单向量 Fluent 请求模型与高级检索功能测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusSearchRequestTest {

    private MilvusClientV2 client;
    private AtengMilvusProperties properties;
    private MilvusTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        template = new MilvusTemplate(client, properties);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        MilvusContextHolder.clear();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "test_article", description = "文章切片向量测试集合")
    public static class ArticleDoc {
        @MilvusId
        private Long id;

        @MilvusPartitionKey
        private String tenantId;

        private String authorId;

        private String title;

        @MilvusVector(dimension = 4)
        private List<Float> vector;
    }

    @Test
    @DisplayName("MilvusSearchRequest - Fluent 构建器与实体推导")
    void testSearchRequestBuilderWithEntity() {
        MilvusSearchRequest<ArticleDoc> request = MilvusSearchRequest.of(ArticleDoc.class)
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(20)
                .offset(5)
                .expr("authorId == 'A100'")
                .groupBy("authorId")
                .radius(0.85)
                .rangeFilter(0.5)
                .consistencyLevel(MilvusConsistencyLevel.STRONG)
                .param("nprobe", 16)
                .params(Map.of("level", 1))
                .partitionNames("p1", "p2");

        assertThat(request.getCollectionName()).isEqualTo("test_article");
        assertThat(request.getAnnsField()).isEqualTo("vector");
        assertThat(request.getTopK()).isEqualTo(20);
        assertThat(request.getOffset()).isEqualTo(5);
        assertThat(request.getGroupByFieldName()).isEqualTo("authorId");
        assertThat(request.getRadius()).isEqualTo(0.85);
        assertThat(request.getRangeFilter()).isEqualTo(0.5);
        assertThat(request.getConsistencyLevel()).isEqualTo(MilvusConsistencyLevel.STRONG);
        assertThat(request.getSearchParams()).containsEntry("nprobe", 16);
        assertThat(request.getSearchParams()).containsEntry("level", 1);
        assertThat(request.getPartitionNames()).containsExactly("p1", "p2");

        Map<String, Object> effectiveSearchParams = request.buildEffectiveSearchParams();
        assertThat(effectiveSearchParams).containsEntry("radius", 0.85);
        assertThat(effectiveSearchParams).containsEntry("range_filter", 0.5);
        assertThat(effectiveSearchParams).containsEntry("nprobe", 16);
        assertThat(effectiveSearchParams).containsEntry("level", 1);
    }

    @Test
    @DisplayName("MilvusSearchRequest - 参数校验前置卫语句防卫")
    void testSearchRequestValidation() {
        // 缺少集合与实体
        MilvusSearchRequest<Object> req1 = MilvusSearchRequest.builder()
                .vector(List.of(0.1f, 0.2f));
        assertThatThrownBy(req1::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionName");

        // Map 类型且未指定集合名称 (防止集合防卫穿透)
        MilvusSearchRequest<Map<String, Object>> reqMap = MilvusSearchRequest.builder()
                .entityClass((Class) Map.class)
                .vector(List.of(0.1f, 0.2f));
        assertThatThrownBy(reqMap::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionName must not be empty");

        // 缺少向量
        MilvusSearchRequest<Object> req2 = MilvusSearchRequest.of("my_collection");
        assertThatThrownBy(req2::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector must not be null");

        // topK 非正数
        MilvusSearchRequest<Object> req3 = MilvusSearchRequest.of("my_collection")
                .vector(List.of(0.1f))
                .topK(0);
        assertThatThrownBy(req3::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be positive");

        // offset 负数
        MilvusSearchRequest<Object> req4 = MilvusSearchRequest.of("my_collection")
                .vector(List.of(0.1f))
                .offset(-1);
        assertThatThrownBy(req4::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset must not be negative");

        // offset + topK 超出 16384 硬限制
        MilvusSearchRequest<Object> reqDeepPage = MilvusSearchRequest.of("my_collection")
                .vector(List.of(0.1f))
                .offset(16380)
                .topK(10);
        assertThatThrownBy(reqDeepPage::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16384");

        // 单独指定 rangeFilter 缺失 radius
        MilvusSearchRequest<Object> reqRange1 = MilvusSearchRequest.of("my_collection")
                .vector(List.of(0.1f))
                .rangeFilter(0.5);
        assertThatThrownBy(reqRange1::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius must not be null when rangeFilter is specified");

        // radius 与 rangeFilter 相等
        MilvusSearchRequest<Object> reqRange2 = MilvusSearchRequest.of("my_collection")
                .vector(List.of(0.1f))
                .radius(0.8)
                .rangeFilter(0.8);
        assertThatThrownBy(reqRange2::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius and rangeFilter must not be equal");

        // radius 为 NaN
        MilvusSearchRequest<Object> reqRange3 = MilvusSearchRequest.of("my_collection")
                .vector(List.of(0.1f))
                .radius(Double.NaN);
        assertThatThrownBy(reqRange3::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius must be a finite number");
    }

    @Test
    @DisplayName("MilvusTemplate.search - GroupBy 与 Range 检索全链路参数传递")
    void testSearchWithGroupByAndRange() {
        TenantContextHolder.setTenantId("tenant_corp");

        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("id", 2001L);
        entityMap.put("authorId", "A100");
        entityMap.put("title", "Milvus 2.4 新特性解读");
        entityMap.put("tenantId", "tenant_corp");

        SearchResp.SearchResult searchResult = SearchResp.SearchResult.builder()
                .id(2001L)
                .score(0.88f)
                .entity(entityMap)
                .build();
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(List.of(searchResult)))
                .build();
        when(client.search(any(SearchReq.class))).thenReturn(mockResp);

        MilvusSearchRequest<ArticleDoc> request = MilvusSearchRequest.of(ArticleDoc.class)
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(10)
                .offset(2)
                .groupBy("authorId")
                .radius(0.9)
                .rangeFilter(0.6)
                .searchParam("ef", 64)
                .roundDecimal(3);

        List<MilvusHit<ArticleDoc>> hits = template.search(request);

        assertThat(hits).hasSize(1);
        MilvusHit<ArticleDoc> hit = hits.get(0);
        assertThat(hit.getId()).isEqualTo(2001L);
        assertThat(hit.getScore()).isEqualTo(0.88f);
        assertThat(hit.getEntity()).isNotNull();
        assertThat(hit.getEntity().getTitle()).isEqualTo("Milvus 2.4 新特性解读");

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq actualReq = captor.getValue();

        assertThat(actualReq.getCollectionName()).isEqualTo("test_article");
        assertThat(actualReq.getTopK()).isEqualTo(10);
        assertThat(actualReq.getOffset()).isEqualTo(2);
        assertThat(actualReq.getGroupByFieldName()).isEqualTo("authorId");
        assertThat(actualReq.getRoundDecimal()).isEqualTo(3);
        assertThat(actualReq.getFilter()).isEqualTo("tenantId == 'tenant_corp'");

        Map<String, Object> searchParams = actualReq.getSearchParams();
        assertThat(searchParams).isNotNull();
        assertThat(searchParams.get("radius")).isEqualTo(0.9);
        assertThat(searchParams.get("range_filter")).isEqualTo(0.6);
        assertThat(searchParams.get("ef")).isEqualTo(64);
    }

    @Test
    @DisplayName("MilvusTemplate.search - 稀疏向量与自定义字典检索")
    void testSearchWithSparseVectorAndDict() {
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("id", "doc_sparse_1");
        entityMap.put("content", "稀疏向量关键词测试");

        SearchResp.SearchResult searchResult = SearchResp.SearchResult.builder()
                .id("doc_sparse_1")
                .score(12.5f)
                .entity(entityMap)
                .build();
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(List.of(searchResult)))
                .build();
        when(client.search(any(SearchReq.class))).thenReturn(mockResp);

        SortedMap<Long, Float> sparseMap = new TreeMap<>();
        sparseMap.put(101L, 0.5f);
        sparseMap.put(205L, 1.2f);

        MilvusSearchRequest<Map<String, Object>> request = MilvusSearchRequest.<Map<String, Object>>of("sparse_collection")
                .entityClass((Class) Map.class)
                .sparseVector(sparseMap)
                .annsField("sparse_vector")
                .topK(5)
                .outputFields("id", "content");

        List<MilvusHit<Map<String, Object>>> hits = template.search(request);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getId()).isEqualTo("doc_sparse_1");
        assertThat(hits.get(0).getEntity()).containsEntry("content", "稀疏向量关键词测试");
        assertThat(hits.get(0).getRawData()).containsEntry("content", "稀疏向量关键词测试");

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq actualReq = captor.getValue();
        assertThat(actualReq.getCollectionName()).isEqualTo("sparse_collection");
        assertThat(actualReq.getAnnsField()).isEqualTo("sparse_vector");
        assertThat(actualReq.getData()).isNotEmpty();
    }

    @Test
    @DisplayName("MilvusTemplate.search - 存量重载方法 100% 向后兼容性全覆盖")
    void testLegacySearchOverloadsBackwardCompatibility() {
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("id", 1001L);
        entityMap.put("authorId", "A200");
        entityMap.put("title", "向后兼容性单测");

        SearchResp.SearchResult searchResult = SearchResp.SearchResult.builder()
                .id(1001L)
                .score(0.95f)
                .entity(entityMap)
                .build();
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(List.of(searchResult)))
                .build();
        when(client.search(any(SearchReq.class))).thenReturn(mockResp);

        List<Float> vector = List.of(0.1f, 0.2f, 0.3f, 0.4f);

        // 1. search(Class<T>, vector, topK)
        List<MilvusHit<ArticleDoc>> r1 = template.search(ArticleDoc.class, vector, 5);
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).getEntity().getTitle()).isEqualTo("向后兼容性单测");

        // 2. search(Class<T>, vector, topK, expr)
        List<MilvusHit<ArticleDoc>> r2 = template.search(ArticleDoc.class, vector, 5, "authorId == 'A200'");
        assertThat(r2).hasSize(1);

        // 3. search(Class<T>, vector, topK, expr, consistencyLevel)
        List<MilvusHit<ArticleDoc>> r3 = template.search(ArticleDoc.class, vector, 5, "authorId == 'A200'", MilvusConsistencyLevel.STRONG);
        assertThat(r3).hasSize(1);

        // 4. search(collectionName, vector, topK)
        List<MilvusHit<Map<String, Object>>> r4 = template.search("test_article", vector, 5);
        assertThat(r4).hasSize(1);
        assertThat(r4.get(0).getRawData()).containsEntry("title", "向后兼容性单测");

        // 5. search(collectionName, vector, topK, expr)
        List<MilvusHit<Map<String, Object>>> r5 = template.search("test_article", vector, 5, "authorId == 'A200'");
        assertThat(r5).hasSize(1);

        // 6. search(collectionName, vector, topK, expr, outputFields, consistencyLevel)
        List<MilvusHit<Map<String, Object>>> r6 = template.search("test_article", vector, 5, "authorId == 'A200'", List.of("id", "title"), MilvusConsistencyLevel.BOUNDED);
        assertThat(r6).hasSize(1);

        // 7. search(collectionName, annsField, vector, topK, expr, outputFields, consistencyLevel)
        List<MilvusHit<Map<String, Object>>> r7 = template.search("test_article", "vector", vector, 5, "authorId == 'A200'", List.of("id", "title"), MilvusConsistencyLevel.BOUNDED);
        assertThat(r7).hasSize(1);

        // 8. search(collectionName, annsField, vector, topK, expr, outputFields, consistencyLevel, entityClass)
        List<MilvusHit<ArticleDoc>> r8 = template.search("test_article", "vector", vector, 5, "authorId == 'A200'", List.of("id", "title"), MilvusConsistencyLevel.BOUNDED, ArticleDoc.class);
        assertThat(r8).hasSize(1);
        assertThat(r8.get(0).getEntity().getTitle()).isEqualTo("向后兼容性单测");

        // 9. search(collectionName, annsField, vector, topK, expr, outputFields, consistencyLevel, entityClass, tenantFieldName)
        List<MilvusHit<ArticleDoc>> r9 = template.search("test_article", "vector", vector, 5, "authorId == 'A200'", List.of("id", "title"), MilvusConsistencyLevel.BOUNDED, ArticleDoc.class, "tenantId");
        assertThat(r9).hasSize(1);
        assertThat(r9.get(0).getEntity().getTitle()).isEqualTo("向后兼容性单测");
    }

    @Test
    @DisplayName("MilvusTemplate.search - 动态切库参数隔离执行")
    void testSearchWithDatabaseName() {
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(Collections.emptyList()))
                .build();
        when(client.search(any(SearchReq.class))).thenAnswer(invocation -> {
            assertThat(MilvusContextHolder.getDatabase()).isEqualTo("tenant_db_finance");
            return mockResp;
        });

        MilvusSearchRequest<Object> request = MilvusSearchRequest.builder()
                .collectionName("finance_collection")
                .vector(List.of(0.1f, 0.2f))
                .databaseName("tenant_db_finance");

        template.search(request);

        // 校验执行完成后 ThreadLocal 上下文被可靠清理
        assertThat(MilvusContextHolder.getDatabase()).isNull();
    }
}
