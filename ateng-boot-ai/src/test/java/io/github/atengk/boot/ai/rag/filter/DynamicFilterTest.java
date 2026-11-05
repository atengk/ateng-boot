package io.github.atengk.boot.ai.rag.filter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 动态元数据权限过滤与租户隔离下推单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("RAG 动态元数据权限过滤与租户隔离下推单元测试")
class DynamicFilterTest {

    @AfterEach
    void tearDown() {
        DynamicFilterContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("DynamicFilterContextHolder 支持线程上下文绑定与 try-with-resources 自动清理")
    void testContextHolderLifecycle() {
        Filter filter = MetadataFilterBuilder.metadataKey("category").isEqualTo("finance");

        try (DynamicFilterContextHolder.DynamicFilterScope scope = DynamicFilterContextHolder.useFilter(filter)) {
            assertThat(DynamicFilterContextHolder.getFilter()).isEqualTo(filter);
        }

        assertThat(DynamicFilterContextHolder.getFilter()).isNull();
    }

    @Test
    @DisplayName("自动感知 TenantContextHolder 并智能生成租户隔离 Filter 下推")
    void testTenantContextFilterResolution() {
        TenantContextHolder.setTenantId("TENANT-1001");

        // 1. 无显式 Filter 时，自动生成 tenant_id == TENANT-1001
        Filter resolved = DynamicFilterContextHolder.resolveFilter(null);
        assertThat(resolved).isNotNull();

        // 2. 忽略租户时，不注入租户 Filter
        TenantContextHolder.setIgnoreTenant(true);
        assertThat(DynamicFilterContextHolder.resolveFilter(null)).isNull();
        TenantContextHolder.setIgnoreTenant(false);

        // 3. 显式 Filter 与租户 Filter 自动执行 AND 复合
        Filter customFilter = MetadataFilterBuilder.metadataKey("category").isEqualTo("tech");
        Filter combined = DynamicFilterContextHolder.resolveFilter(customFilter);
        assertThat(combined).isNotNull();
    }

    @Test
    @DisplayName("DynamicFilterContentRetriever 正确下推复合 Filter 到底层 EmbeddingStore")
    @SuppressWarnings("unchecked")
    void testDynamicFilterContentRetrieverPushdown() {
        EmbeddingStore<TextSegment> mockStore = Mockito.mock(EmbeddingStore.class);
        EmbeddingModel mockModel = Mockito.mock(EmbeddingModel.class);

        when(mockModel.embed(any(String.class))).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));

        TextSegment segment = TextSegment.from("安全规范切片", Metadata.from("tenant_id", "T-888"));
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.95, "match-id-1", Embedding.from(new float[]{0.1f, 0.2f}), segment);
        when(mockStore.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        // 激活当前租户
        TenantContextHolder.setTenantId("T-888");

        Filter baseFilter = MetadataFilterBuilder.metadataKey("status").isEqualTo("PUBLISHED");
        DynamicFilterContentRetriever retriever = new DynamicFilterContentRetriever(
                mockStore,
                mockModel,
                5,
                0.7,
                baseFilter
        );

        List<Content> contents = retriever.retrieve(Query.from("安全策略"));

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).textSegment().text()).isEqualTo("安全规范切片");

        // 验证捕获到的 request 中 filter 必须存在且非空
        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(mockStore).search(captor.capture());
        EmbeddingSearchRequest captured = captor.getValue();
        assertThat(captured.filter()).isNotNull();
        assertThat(captured.maxResults()).isEqualTo(5);
        assertThat(captured.minScore()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("EmbeddingTemplate.search 全真验证动态元数据与租户隔离下推检索")
    void testEmbeddingTemplateSearchWithDynamicFilter() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        EmbeddingModel mockModel = Mockito.mock(EmbeddingModel.class);
        when(mockModel.embed(any(String.class))).thenReturn(Response.from(Embedding.from(new float[]{1.0f, 0.0f})));

        EmbeddingTemplate template = new EmbeddingTemplate(mockModel);

        // 写入租户 A 与租户 B 的两个切片
        TextSegment segA = TextSegment.from("租户 A 的技术架构规范", Metadata.from("tenant_id", "TENANT-A").put("category", "tech"));
        TextSegment segB = TextSegment.from("租户 B 的财务账单明细", Metadata.from("tenant_id", "TENANT-B").put("category", "finance"));

        store.add(Embedding.from(new float[]{1.0f, 0.0f}), segA);
        store.add(Embedding.from(new float[]{1.0f, 0.0f}), segB);

        // 1. 无租户过滤时，能查到 2 条
        List<EmbeddingMatch<TextSegment>> matchesAll = template.search(store, "技术", 10, null, null);
        assertThat(matchesAll).hasSize(2);

        // 2. 绑定租户 TENANT-A，检索时自动下推租户隔离，仅返回租户 A 的切片
        TenantContextHolder.setTenantId("TENANT-A");
        List<EmbeddingMatch<TextSegment>> matchesTenantA = template.search(store, "技术", 10, null, null);
        assertThat(matchesTenantA).hasSize(1);
        assertThat(matchesTenantA.get(0).embedded().text()).isEqualTo("租户 A 的技术架构规范");

        // 3. 叠加业务维度 Filter (category == tech)
        Filter categoryFilter = MetadataFilterBuilder.metadataKey("category").isEqualTo("tech");
        List<EmbeddingMatch<TextSegment>> matchesCategory = template.search(store, "技术", 10, null, categoryFilter);
        assertThat(matchesCategory).hasSize(1);

        // 4. 若传入不匹配的业务 Filter (category == finance)，在租户 A 作用域下无交集，返回空
        Filter financeFilter = MetadataFilterBuilder.metadataKey("category").isEqualTo("finance");
        List<EmbeddingMatch<TextSegment>> matchesEmpty = template.search(store, "技术", 10, null, financeFilter);
        assertThat(matchesEmpty).isEmpty();
    }
}
