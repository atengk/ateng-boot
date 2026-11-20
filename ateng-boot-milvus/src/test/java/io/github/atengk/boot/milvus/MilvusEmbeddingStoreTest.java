package io.github.atengk.boot.milvus;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.langchain4j.MilvusEmbeddingStore;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MilvusEmbeddingStore 单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusEmbeddingStoreTest {

    private MilvusTemplate template;
    private MilvusEmbeddingStore store;

    @BeforeEach
    void setUp() {
        template = mock(MilvusTemplate.class);
        store = MilvusEmbeddingStore.builder()
                .template(template)
                .collectionName("rag_kb")
                .dimension(3)
                .autoCreateCollection(true)
                .build();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        MilvusContextHolder.clear();
    }

    @Test
    @DisplayName("add & addAll - 文本切片与向量持久化及租户自动提取")
    @SuppressWarnings("unchecked")
    void testAddAndAddAll() {
        TenantContextHolder.setTenantId("corp_rag");
        when(template.hasCollection("rag_kb")).thenReturn(true);

        Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        TextSegment segment = TextSegment.from("RAG 检索增强架构", Metadata.from("docId", "doc_001"));

        // 1. add 单个
        String id = store.add(embedding, segment);
        assertThat(id).isNotBlank();

        ArgumentCaptor<List<Map<String, Object>>> insertCaptor = ArgumentCaptor.forClass(List.class);
        verify(template).insert(eq("rag_kb"), insertCaptor.capture(), any());
        List<Map<String, Object>> inserted = insertCaptor.getValue();
        assertThat(inserted).hasSize(1);
        Map<String, Object> row = inserted.get(0);
        assertThat(row.get("text")).isEqualTo("RAG 检索增强架构");
        assertThat(row.get("docId")).isEqualTo("doc_001");
        assertThat(row.get("tenant_id")).isEqualTo("corp_rag");

        // 2. addAll 批量
        List<String> ids = store.addAll(List.of(embedding), List.of(segment));
        assertThat(ids).hasSize(1);
        verify(template).insertBatch(eq("rag_kb"), anyList(), any());
    }

    @Test
    @DisplayName("search - 语义相似度召回与元数据重构")
    void testSearch() {
        when(template.hasCollection("rag_kb")).thenReturn(true);

        Map<String, Object> hitData = new HashMap<>();
        hitData.put("id", "vec_100");
        hitData.put("text", "Milvus 官方驱动架构");
        hitData.put("author", "Ateng");
        hitData.put("score", 0.92f);

        MilvusHit<Map<String, Object>> hit = MilvusHit.<Map<String, Object>>builder()
                .id("vec_100")
                .score(0.92f)
                .rawData(hitData)
                .build();

        when(template.<Map<String, Object>>search(eq("rag_kb"), eq("vector"), anyList(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(hit));

        Embedding queryEmbedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .minScore(0.8)
                .filter(new IsEqualTo("author", "Ateng"))
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        assertThat(result.matches()).hasSize(1);

        EmbeddingMatch<TextSegment> match = result.matches().get(0);
        assertThat(match.embeddingId()).isEqualTo("vec_100");
        assertThat(match.score()).isEqualTo(0.92, org.assertj.core.data.Offset.offset(0.001));
        assertThat(match.embedded()).isNotNull();
        assertThat(match.embedded().text()).isEqualTo("Milvus 官方驱动架构");
        assertThat(match.embedded().metadata().getString("author")).isEqualTo("Ateng");

        // minScore 阈值过滤验证: 若 minScore 为 0.95 则过滤为 0 条
        EmbeddingSearchRequest highThresholdReq = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .minScore(0.95)
                .build();
        EmbeddingSearchResult<TextSegment> filteredResult = store.search(highThresholdReq);
        assertThat(filteredResult.matches()).isEmpty();
    }

    @Test
    @DisplayName("remove & removeAll - 删除数据与 Filter 过滤删除")
    void testRemove() {
        when(template.hasCollection("rag_kb")).thenReturn(true);

        store.remove("id_123");
        verify(template).deleteByIds(eq("rag_kb"), eq(List.of("id_123")), any());

        store.removeAll(List.of("id_456", "id_789"));
        verify(template).deleteByIds(eq("rag_kb"), eq(List.of("id_456", "id_789")), any());

        store.removeAll(new IsEqualTo("category", "deprecated"));
        verify(template).deleteByExpr(eq("rag_kb"), eq("category == 'deprecated'"), any());

        store.removeAll();
        verify(template).deleteByExpr(eq("rag_kb"), eq("id != ''"), any());
    }

    @Test
    @DisplayName("removeAll - 多租户上下文下织入租户隔离过滤条件")
    void testRemoveAllWithTenant() {
        when(template.hasCollection("rag_kb")).thenReturn(true);
        TenantContextHolder.setTenantId("tenant_999");

        store.removeAll();
        verify(template).deleteByExpr(eq("rag_kb"), eq("tenant_id == 'tenant_999'"), any());
    }

    @Test
    @DisplayName("对抗性测试 - 验证底层驱动异常与业务异常的受控转译与传递")
    void testExceptionTranslation() {
        when(template.hasCollection("rag_kb")).thenReturn(true);
        when(template.<Map<String, Object>>search(eq("rag_kb"), eq("vector"), anyList(), anyInt(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException("Milvus 连接超时熔断"));

        Embedding queryEmbedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .build();

        assertThatThrownBy(() -> store.search(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Milvus 连接超时熔断");
    }

    @Test
    @DisplayName("ensureCollectionExists - 自动建表与加载平滑探测")
    void testEnsureCollectionExists() {
        when(template.hasCollection("rag_kb")).thenReturn(false);

        // 触发自愈初始化
        store.ensureCollectionExists();

        verify(template).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());
    }

    @Test
    @DisplayName("ensureCollectionExists - ReentrantLock 并发互斥防 Pinning 且仅初始化一次")
    void testConcurrentEnsureCollectionExistsWithoutSynchronizedPinning() throws Exception {
        Method ensureMethod = MilvusEmbeddingStore.class.getMethod("ensureCollectionExists");
        assertThat(Modifier.isSynchronized(ensureMethod.getModifiers())).isFalse();

        when(template.resolveCurrentDatabase()).thenReturn("default");
        when(template.hasCollection("rag_kb")).thenReturn(false);

        AtomicInteger createCalls = new AtomicInteger(0);
        doAnswer(inv -> {
            Thread.sleep(30);
            createCalls.incrementAndGet();
            return null;
        }).when(template).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());

        int threads = 8;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setThreadFactory(new NamedThreadFactory("milvus-store-test-"));
        pool.initialize();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        startGate.await();
                        store.ensureCollectionExists();
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();
            assertThat(createCalls.get()).isEqualTo(1);
            verify(template, times(1)).hasCollection("rag_kb");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("ensureCollectionExists - 多库切换自动按需建表与 resetInitialized 对称重置")
    void testMultiDatabaseInitializationAndReset() {
        when(template.hasCollection("rag_kb")).thenReturn(false);

        // 1. db_alpha 首次触发初始化，再次调用命中缓存不重复探测
        when(template.resolveCurrentDatabase()).thenReturn("db_alpha");
        store.ensureCollectionExists();
        store.ensureCollectionExists();
        assertThat(store.isInitialized()).isTrue();
        verify(template, times(1)).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());

        // 2. 切换至 db_beta 后自动为新库补建同名集合
        when(template.resolveCurrentDatabase()).thenReturn("db_beta");
        assertThat(store.isInitialized()).isFalse();
        store.ensureCollectionExists();
        assertThat(store.isInitialized()).isTrue();
        verify(template, times(2)).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());

        // 3. 调用 resetInitialized 后允许重新触发初始化探测
        store.resetInitialized();
        assertThat(store.isInitialized()).isFalse();
        store.ensureCollectionExists();
        verify(template, times(3)).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());
    }

    @Test
    @DisplayName("ensureCollectionExists - 初始化异常时释放锁且不写入缓存以便后续自愈")
    void testEnsureCollectionExistsFailureReleasesLockAndRetries() {
        when(template.resolveCurrentDatabase()).thenReturn("db_retry");
        when(template.hasCollection("rag_kb")).thenReturn(false);
        doThrow(new BusinessException("建表 RPC 瞬态超时"))
                .doNothing()
                .when(template).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());

        assertThatThrownBy(() -> store.ensureCollectionExists())
                .isInstanceOf(BusinessException.class);
        assertThat(store.isInitialized()).isFalse();

        // 第二次调用能够成功获取锁并完成自愈
        store.ensureCollectionExists();
        assertThat(store.isInitialized()).isTrue();
        verify(template, times(2)).createCollectionAndIndex(eq("rag_kb"), eq("vector"), eq(3), any(), any());
    }
}
