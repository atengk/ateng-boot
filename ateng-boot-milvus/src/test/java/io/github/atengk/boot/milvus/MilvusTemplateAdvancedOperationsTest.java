package io.github.atengk.boot.milvus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.model.AnnSearch;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusHybridSearchRequest;
import io.github.atengk.boot.milvus.model.ReRanker;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryIteratorReq;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MilvusTemplate 高级门面 (多向量混合检索/游标流式遍历/动态切库) 单测
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusTemplateAdvancedOperationsTest {

    private MilvusClientV2 client;
    private AtengMilvusProperties properties;
    private MilvusTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        template = new MilvusTemplate(client, properties);
        TenantContextHolder.clear();
        MilvusContextHolder.clear();
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
    @MilvusCollection(name = "hybrid_doc")
    static class HybridDoc {
        @MilvusId
        private Long id;
        private String title;
        @MilvusPartitionKey
        private String tenantId;
        @MilvusVector(dimension = 3)
        private List<Float> denseVector;
    }

    @Test
    @DisplayName("hybridSearch - 稠密向量与稀疏向量混合检索融合 RRF 重排")
    void testHybridSearchWithRRF() {
        TenantContextHolder.setTenantId("hybrid_tenant");

        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("id", 101L);
        entityMap.put("title", "混合检索技术");
        entityMap.put("tenant_id", "hybrid_tenant");

        SearchResp.SearchResult hitResult = SearchResp.SearchResult.builder()
                .id(101L)
                .score(0.88f)
                .entity(entityMap)
                .build();
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(List.of(hitResult)))
                .build();
        when(client.hybridSearch(any(HybridSearchReq.class))).thenReturn(mockResp);

        // 1. 稠密向量通道
        AnnSearch denseSearch = AnnSearch.of("denseVector", List.of(0.1f, 0.2f, 0.3f), 20, "status == 1");

        // 2. 稀疏向量通道 (SPLADE / BM25)
        TreeMap<Long, Float> sparseMap = new TreeMap<>();
        sparseMap.put(1001L, 1.5f);
        sparseMap.put(2002L, 0.8f);
        AnnSearch sparseSearch = AnnSearch.ofSparse("sparseVector", sparseMap, 20);

        MilvusHybridSearchRequest<HybridDoc> request = MilvusHybridSearchRequest.of(HybridDoc.class)
                .searches(denseSearch, sparseSearch)
                .rrf(60)
                .topK(10)
                .consistencyLevel(MilvusConsistencyLevel.STRONG);

        List<MilvusHit<HybridDoc>> hits = template.hybridSearch(request);

        assertThat(hits).hasSize(1);
        MilvusHit<HybridDoc> hit = hits.get(0);
        assertThat(hit.getId()).isEqualTo(101L);
        assertThat(hit.getEntity()).isNotNull();
        assertThat(hit.getEntity().getTitle()).isEqualTo("混合检索技术");

        ArgumentCaptor<HybridSearchReq> reqCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);
        verify(client).hybridSearch(reqCaptor.capture());
        HybridSearchReq capturedReq = reqCaptor.getValue();
        assertThat(capturedReq.getCollectionName()).isEqualTo("hybrid_doc");
        assertThat(capturedReq.getSearchRequests()).hasSize(2);
        // 验证多租户条件已自动织入各个子检索表达式
        assertThat(capturedReq.getSearchRequests().get(0).getExpr()).isEqualTo("(status == 1) and (tenant_id == 'hybrid_tenant')");
        assertThat(capturedReq.getSearchRequests().get(1).getExpr()).isEqualTo("tenant_id == 'hybrid_tenant'");
    }

    @Test
    @DisplayName("hybridSearch - 加权评分融合重排 WeightedRanker")
    void testHybridSearchWithWeighted() {
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(List.of()))
                .build();
        when(client.hybridSearch(any(HybridSearchReq.class))).thenReturn(mockResp);

        AnnSearch dense = AnnSearch.of("v1", List.of(0.1f, 0.2f, 0.3f), 10);
        AnnSearch sparse = AnnSearch.of("v2", List.of(0.4f, 0.5f, 0.6f), 10);

        MilvusHybridSearchRequest<Map<String, Object>> request = MilvusHybridSearchRequest.of("multi_col")
                .searches(dense, sparse)
                .weighted(0.7f, 0.3f)
                .topK(5);

        List<MilvusHit<Map<String, Object>>> hits = template.hybridSearch(request);
        assertThat(hits).isEmpty();

        ArgumentCaptor<HybridSearchReq> reqCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);
        verify(client).hybridSearch(reqCaptor.capture());
        assertThat(reqCaptor.getValue().getRanker()).isNotNull();
    }

    @Test
    @DisplayName("streamingQuery - 游标流式遍历突破 16384 深度限制并安全关闭游标")
    void testStreamingQuery() {
        QueryIterator mockIterator = mock(QueryIterator.class);
        when(client.queryIterator(any(QueryIteratorReq.class))).thenReturn(mockIterator);

        // 模拟第 1 批数据
        QueryResultsWrapper.RowRecord r1 = mock(QueryResultsWrapper.RowRecord.class);
        when(r1.getFieldValues()).thenReturn(Map.of("id", 1L, "title", "A"));
        QueryResultsWrapper.RowRecord r2 = mock(QueryResultsWrapper.RowRecord.class);
        when(r2.getFieldValues()).thenReturn(Map.of("id", 2L, "title", "B"));

        // 模拟第 2 批数据
        QueryResultsWrapper.RowRecord r3 = mock(QueryResultsWrapper.RowRecord.class);
        when(r3.getFieldValues()).thenReturn(Map.of("id", 3L, "title", "C"));

        // 第 3 次返回空列表表示遍历结束
        when(mockIterator.next())
                .thenReturn(List.of(r1, r2))
                .thenReturn(List.of(r3))
                .thenReturn(List.of());

        AtomicInteger batchCount = new AtomicInteger();
        List<Map<String, Object>> allRows = new ArrayList<>();

        template.streamingQuery("big_collection", "status == 1", 2, batch -> {
            batchCount.incrementAndGet();
            allRows.addAll(batch);
        });

        assertThat(batchCount.get()).isEqualTo(2);
        assertThat(allRows).hasSize(3);
        // 验证确定性资源释放
        verify(mockIterator).close();
    }

    @Test
    @DisplayName("streamingQuery - 消费异常时游标必须在 finally 中确定性关闭")
    void testStreamingQueryExceptionSafety() {
        QueryIterator mockIterator = mock(QueryIterator.class);
        when(client.queryIterator(any(QueryIteratorReq.class))).thenReturn(mockIterator);

        QueryResultsWrapper.RowRecord r1 = mock(QueryResultsWrapper.RowRecord.class);
        when(r1.getFieldValues()).thenReturn(Map.of("id", 1L));
        when(mockIterator.next()).thenReturn(List.of(r1));

        assertThatThrownBy(() -> template.streamingQuery("fail_col", null, batch -> {
            throw new RuntimeException("消费回调异常");
        })).hasMessageContaining("消费回调异常");

        // 验证即便发生业务异常，游标句柄也被绝对可靠地释放
        verify(mockIterator).close();
    }

    @Test
    @DisplayName("useDatabase - 线程级闭包切库与现场恢复")
    void testUseDatabaseClosure() {
        assertThat(template.resolveCurrentDatabase()).isEqualTo("default");

        AtomicBoolean insideExecuted = new AtomicBoolean(false);
        template.useDatabase("sales_database", () -> {
            insideExecuted.set(true);
            assertThat(template.resolveCurrentDatabase()).isEqualTo("sales_database");
            assertThat(MilvusContextHolder.getDatabase()).isEqualTo("sales_database");
        });

        assertThat(insideExecuted.get()).isTrue();
        // 闭包结束后现场自动恢复
        assertThat(template.resolveCurrentDatabase()).isEqualTo("default");
        assertThat(MilvusContextHolder.getDatabase()).isNull();
    }

    @Test
    @DisplayName("useDatabase - 闭包抛出异常时必须在 finally 强制恢复现场")
    void testUseDatabaseExceptionRestoresContext() {
        MilvusContextHolder.setDatabase("prior_db");

        assertThatThrownBy(() -> template.useDatabase("temp_db", () -> {
            throw new IllegalStateException("切库后执行失败");
        })).isInstanceOf(IllegalStateException.class);

        // 恢复为 prior_db
        assertThat(MilvusContextHolder.getDatabase()).isEqualTo("prior_db");
    }

    @Test
    @DisplayName("switchDatabase - 底层客户端动态切换数据库")
    void testSwitchDatabase() throws InterruptedException {
        template.switchDatabase("new_database");
        verify(client).useDatabase("new_database");
    }
}
