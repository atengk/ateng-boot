package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.atengk.boot.ai.rag.filter.DynamicFilterContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原生轻量 BM25 稀疏关键词检索器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("原生轻量 BM25 稀疏关键词检索器单元测试")
class Bm25KeywordRetrieverTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        DynamicFilterContextHolder.clear();
    }

    @Test
    @DisplayName("标准关键词词频打分与召回排序")
    void testBm25Scoring() {
        TextSegment seg1 = TextSegment.from("Spring Boot 自动装配原理与 Starter 机制详解");
        TextSegment seg2 = TextSegment.from("Redis 分布式缓存与多级缓存击穿治理");
        TextSegment seg3 = TextSegment.from("Spring Boot 与 Redis 集成最佳实践");

        Bm25KeywordRetriever retriever = new Bm25KeywordRetriever(
                List.of(seg1, seg2, seg3),
                2
        );

        List<Content> results = retriever.retrieve(Query.from("Spring Boot 自动装配"));

        assertThat(results).isNotEmpty();
        // seg1 完全匹配“Spring Boot 自动装配”，得分最高排首位
        assertThat(results.get(0).textSegment().text()).contains("自动装配原理");
    }

    @Test
    @DisplayName("空查询或无匹配词汇时返回空列表")
    void testBm25EmptyOrNoMatch() {
        Bm25KeywordRetriever retriever = new Bm25KeywordRetriever(
                List.of(TextSegment.from("Java 虚拟机垃圾回收机制")),
                3
        );

        assertThat(retriever.retrieve(null)).isEmpty();
        assertThat(retriever.retrieve(Query.from("Python Django"))).isEmpty();
    }

    @Test
    @DisplayName("增量倒排索引支持 addSegments、removeByDocId、rebuild 与 clear 生命周期闭环")
    void testIncrementalInvertedIndexLifecycle() {
        Bm25KeywordRetriever retriever = new Bm25KeywordRetriever(10);
        assertThat(retriever.size()).isZero();

        TextSegment doc1Seg1 = TextSegment.from("微内核自动装配架构设计规范", Metadata.from("doc_id", "doc-1"));
        TextSegment doc1Seg2 = TextSegment.from("云原生可观测指标监控方案", Metadata.from("doc_id", "doc-1"));
        TextSegment doc2Seg1 = TextSegment.from("分布式事务 Seata 与自动装配实践", Metadata.from("doc_id", "doc-2"));

        retriever.addSegments(List.of(doc1Seg1, doc1Seg2, doc2Seg1));
        assertThat(retriever.size()).isEqualTo(3);

        List<Content> beforeDelete = retriever.retrieve(Query.from("可观测指标"));
        assertThat(beforeDelete).hasSize(1);
        assertThat(beforeDelete.get(0).textSegment().text()).contains("可观测指标");

        // 按 doc_id 增量剔除 doc-1 全部切片
        int removed = retriever.removeByDocId("doc-1");
        assertThat(removed).isEqualTo(2);
        assertThat(retriever.size()).isEqualTo(1);
        assertThat(retriever.retrieve(Query.from("可观测指标"))).isEmpty();

        List<Content> doc2Results = retriever.retrieve(Query.from("Seata"));
        assertThat(doc2Results).hasSize(1);
        assertThat(doc2Results.get(0).textSegment().metadata().getString("doc_id")).isEqualTo("doc-2");

        // 全量重建替换现有语料
        retriever.rebuild(List.of(TextSegment.from("全新向量混合检索引擎升级手册", Metadata.from("doc_id", "doc-3"))));
        assertThat(retriever.size()).isEqualTo(1);
        assertThat(retriever.retrieve(Query.from("Seata"))).isEmpty();
        assertThat(retriever.retrieve(Query.from("混合检索"))).hasSize(1);

        retriever.clear();
        assertThat(retriever.size()).isZero();
        assertThat(retriever.retrieve(Query.from("混合检索"))).isEmpty();
    }

    @Test
    @DisplayName("多租户上下文与 DynamicFilter 元数据过滤下推，严防跨租户稀疏召回越权")
    void testTenantAndDynamicFilterIsolation() {
        Bm25KeywordRetriever retriever = new Bm25KeywordRetriever(10);
        retriever.addSegments(List.of(
                TextSegment.from("订单核心交易限流降级预案",
                        Metadata.from(java.util.Map.of("doc_id", "d1", "tenant_id", "tenant-a", "dept", "finance"))),
                TextSegment.from("订单核心交易限流灰度发布手册",
                        Metadata.from(java.util.Map.of("doc_id", "d2", "tenant_id", "tenant-b", "dept", "ops"))),
                TextSegment.from("订单核心交易限流公共基线规范",
                        Metadata.from("doc_id", "d3"))
        ));

        // 租户 A 仅可命中租户 A 语料，绝不越权命中租户 B 或无租户标签文档
        TenantContextHolder.setTenantId("tenant-a");
        List<Content> tenantAResults = retriever.retrieve(Query.from("限流"));
        assertThat(tenantAResults).hasSize(1);
        assertThat(tenantAResults.get(0).textSegment().metadata().getString("tenant_id")).isEqualTo("tenant-a");

        // 切换到租户 B + 动态部门过滤条件
        TenantContextHolder.setTenantId("tenant-b");
        DynamicFilterContextHolder.setFilter(MetadataFilterBuilder.metadataKey("dept").isEqualTo("ops"));
        List<Content> tenantBFiltered = retriever.retrieve(Query.from("限流"));
        assertThat(tenantBFiltered).hasSize(1);
        assertThat(tenantBFiltered.get(0).textSegment().metadata().getString("doc_id")).isEqualTo("d2");

        // 动态过滤器否定命中时返回空
        DynamicFilterContextHolder.setFilter(MetadataFilterBuilder.metadataKey("dept").isEqualTo("finance"));
        assertThat(retriever.retrieve(Query.from("限流"))).isEmpty();

        // 忽略租户隔离且清除动态过滤器时可全局命中全部语料
        DynamicFilterContextHolder.clear();
        TenantContextHolder.setIgnoreTenant(true);
        assertThat(retriever.retrieve(Query.from("限流"))).hasSize(3);
    }

    @Test
    @DisplayName("兼容动态 Supplier 构造视图与超参数防御性校验")
    void testSupplierCompatibilityAndValidation() {
        AtomicReference<List<TextSegment>> backing = new AtomicReference<>(List.of(
                TextSegment.from("动态语料第一条：Redis 持久化 RDB", Metadata.from("tenant_id", "t1")),
                TextSegment.from("动态语料第二条：Redis 持久化 AOF", Metadata.from("tenant_id", "t2"))
        ));
        Bm25KeywordRetriever supplierRetriever = new Bm25KeywordRetriever(backing::get, 5, 1.5, 0.75);
        assertThat(supplierRetriever.size()).isEqualTo(2);

        TenantContextHolder.setTenantId("t2");
        List<Content> results = supplierRetriever.retrieve(Query.from("持久化"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).textSegment().text()).contains("AOF");

        assertThatThrownBy(() -> new Bm25KeywordRetriever(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bm25KeywordRetriever(() -> List.of(), 5, -0.1, 0.75))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bm25KeywordRetriever(() -> List.of(), 5, 1.2, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("高并发读写竞争下 ReentrantLock 守护倒排索引状态一致性")
    void testConcurrentAddRemoveAndRetrieve() throws Exception {
        Bm25KeywordRetriever retriever = new Bm25KeywordRetriever(20);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(6);
        executor.setThreadNamePrefix("bm25-test-");
        executor.initialize();

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int idx = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    String docId = "doc-" + idx;
                    retriever.addSegments(List.of(
                            TextSegment.from("并发倒排索引压测文档内容编号 " + idx, Metadata.from("doc_id", docId))
                    ));
                    retriever.retrieve(Query.from("倒排索引 压测"));
                    if (idx % 2 == 0) {
                        retriever.removeByDocId(docId);
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            assertThat(retriever.size()).isEqualTo(10);
            assertThat(retriever.retrieve(Query.from("倒排索引"))).isNotEmpty();
        } finally {
            executor.shutdown();
        }
    }
}
