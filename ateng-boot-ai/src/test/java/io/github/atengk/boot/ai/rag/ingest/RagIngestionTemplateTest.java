package io.github.atengk.boot.ai.rag.ingest;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.rag.parser.DocumentParserManager;
import io.github.atengk.boot.ai.rag.parser.HtmlDocumentParser;
import io.github.atengk.boot.ai.rag.parser.TextDocumentParser;
import io.github.atengk.boot.ai.rag.splitter.RecursiveCharacterSplitter;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端 ETL Ingestion 管道与幂等去重生命周期治理 (RagIngestionTemplate) 全场景单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("RAG 端到端 Ingestion 管道门面 (RagIngestionTemplate) 单元测试")
class RagIngestionTemplateTest {

    private InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private EmbeddingTemplate embeddingTemplate;
    private DocumentParserManager parserManager;
    private RecursiveCharacterSplitter splitter;
    private RagIngestionTemplate ingestionTemplate;

    @BeforeEach
    void setUp() {
        embeddingStore = new InMemoryEmbeddingStore<>();

        // Mock 离线向量化模型，固定返回 4 维单位向量
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<TextSegment> segs = inv.getArgument(0);
            List<Embedding> embeddings = segs.stream()
                    .map(s -> new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                    .toList();
            return Response.from(embeddings);
        });
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f})));

        embeddingTemplate = new EmbeddingTemplate(embeddingModel, null, null, embeddingStore, null, null);
        parserManager = new DocumentParserManager(new TextDocumentParser(), new HtmlDocumentParser());
        splitter = new RecursiveCharacterSplitter(25, 5);

        ingestionTemplate = new RagIngestionTemplate(parserManager, embeddingTemplate, splitter, embeddingStore);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        if (embeddingStore != null) {
            embeddingStore.removeAll();
        }
    }

    @Test
    @DisplayName("端到端 Ingestion：解析、元数据挂载、切片分块、向量化与入库全流程走通")
    void testIngestPlainTextEndToEnd() {
        String docId = "DOC-TECH-001";
        String docName = "architecture.md";
        String content = "第一章节：系统总体架构与技术基线设计。\n\n" +
                "第二章节：分布式微服务与基础设施治理。\n\n" +
                "第三章节：高并发缓存与数据库读写分离拓扑。";

        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), docName);
        IngestOptions options = IngestOptions.builder()
                .docId(docId)
                .docName(docName)
                .customMetadata(Map.of("department", "R&D", "classification", "INTERNAL"))
                .build();

        IngestResult result = ingestionTemplate.ingest(resource, options);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isSkipped()).isFalse();
        assertThat(result.getDocId()).isEqualTo(docId);
        assertThat(result.getDocName()).isEqualTo(docName);
        assertThat(result.getDocHash()).isNotBlank();
        assertThat(result.getChunkCount()).isGreaterThanOrEqualTo(3);
        assertThat(result.getSegmentIds()).hasSize(result.getChunkCount());
        assertThat(result.getExecutionDuration()).isNotNull();

        // 验证向量库中的每一个切片是否挂载了规范化治理元数据
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId))
                        .maxResults(10)
                        .build()
        );

        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        assertThat(matches).hasSize(result.getChunkCount());

        for (int i = 0; i < matches.size(); i++) {
            TextSegment seg = matches.get(i).embedded();
            Metadata meta = seg.metadata();
            assertThat(meta.getString("doc_id")).isEqualTo(docId);
            assertThat(meta.getString("doc_name")).isEqualTo(docName);
            assertThat(meta.getString("doc_hash")).isEqualTo(result.getDocHash());
            assertThat(meta.getString("created_at")).isNotBlank();
            assertThat(meta.getInteger("chunk_index")).isNotNull();
            assertThat(meta.getString("department")).isEqualTo("R&D");
            assertThat(meta.getString("classification")).isEqualTo("INTERNAL");
        }
    }

    @Test
    @DisplayName("上下文透传：自动从 TenantContextHolder 与 UserContextHolder 提取租户与用户标识并写入切片")
    void testIngestWithContextPropagation() {
        TenantContextHolder.setTenantId("TENANT-ENTERPRISE-01");
        UserContextHolder.setUserId("USER-ADMIN-88");

        String docId = "DOC-CONTEXT-002";
        IngestResult result = ingestionTemplate.ingest("测试租户与用户上下文透传的内容。", "test.txt",
                IngestOptions.builder().docId(docId).build());

        assertThat(result.getTenantId()).isEqualTo("TENANT-ENTERPRISE-01");

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId))
                        .maxResults(5)
                        .build()
        );

        assertThat(searchResult.matches()).isNotEmpty();
        Metadata meta = searchResult.matches().get(0).embedded().metadata();
        assertThat(meta.getString("tenant_id")).isEqualTo("TENANT-ENTERPRISE-01");
        assertThat(meta.getString("user_id")).isEqualTo("USER-ADMIN-88");
    }

    @Test
    @DisplayName("OVERWRITE 策略：文档内容更新后重新入库，自动级联清理旧向量切片并写入新切片")
    void testOverwriteDeduplicationPolicy() {
        String docId = "DOC-OVERWRITE-003";

        // 1. 写入版本 1
        String v1 = "这是文档第一版本的内容，包含两个基础段落。\n\n第二段说明了最初的产品规范。";
        IngestResult r1 = ingestionTemplate.ingest(v1, "spec.txt", IngestOptions.builder()
                .docId(docId)
                .policy(DeduplicationPolicy.OVERWRITE)
                .build());
        assertThat(r1.getChunkCount()).isGreaterThanOrEqualTo(2);

        // 2. 覆盖写入版本 2 (文字更少，仅一个简短段落)
        String v2 = "这是文档第二版本的内容，大幅删减重构。";
        IngestResult r2 = ingestionTemplate.ingest(v2, "spec.txt", IngestOptions.builder()
                .docId(docId)
                .policy(DeduplicationPolicy.OVERWRITE)
                .build());

        assertThat(r2.getDocHash()).isNotEqualTo(r1.getDocHash());
        assertThat(r2.getChunkCount()).isEqualTo(1);

        // 3. 检索验证：库内仅存在版本 2 的 1 个切片，版本 1 的旧切片已被干净剔除
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId))
                        .maxResults(10)
                        .build()
        );

        assertThat(searchResult.matches()).hasSize(1);
        assertThat(searchResult.matches().get(0).embedded().text()).contains("第二版本的内容");
    }

    @Test
    @DisplayName("SKIP 策略：相同 doc_id 且内容 SHA-256 哈希未变时，幂等跳过重复解析与写入")
    void testSkipDeduplicationPolicy() {
        String docId = "DOC-SKIP-004";
        String content = "这是一篇不会发生任何修改的静态归档制度文档内容。";

        IngestOptions options = IngestOptions.builder()
                .docId(docId)
                .docName("static.txt")
                .policy(DeduplicationPolicy.SKIP)
                .build();

        // 1. 首次入库
        IngestResult firstResult = ingestionTemplate.ingest(content, "static.txt", options);
        assertThat(firstResult.isSuccess()).isTrue();
        assertThat(firstResult.isSkipped()).isFalse();
        assertThat(firstResult.getChunkCount()).isEqualTo(1);

        // 2. 相同内容重复入库
        IngestResult secondResult = ingestionTemplate.ingest(content, "static.txt", options);
        assertThat(secondResult.isSuccess()).isTrue();
        assertThat(secondResult.isSkipped()).isTrue();
        assertThat(secondResult.getChunkCount()).isEqualTo(0);
        assertThat(secondResult.getDocHash()).isEqualTo(firstResult.getDocHash());

        // 3. 库内依然只有 1 条记录，未产生任何冗余向量垃圾
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId))
                        .maxResults(10)
                        .build()
        );
        assertThat(searchResult.matches()).hasSize(1);
    }

    @Test
    @DisplayName("deleteDocument: 对称级联清理，依据 doc_id 彻底物理删除该文档所有关联切片")
    void testCascadingDeleteDocument() {
        String docId1 = "DOC-DEL-005";
        String docId2 = "DOC-KEEP-006";

        ingestionTemplate.ingest("文档1内容，用于验证删除。", "doc1.txt", IngestOptions.ofDocId(docId1));
        ingestionTemplate.ingest("文档2内容，验证保留隔离。", "doc2.txt", IngestOptions.ofDocId(docId2));

        // 执行级联删除
        ingestionTemplate.deleteDocument(docId1);

        // 验证 docId1 已被清空
        EmbeddingSearchResult<TextSegment> res1 = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId1))
                        .maxResults(5)
                        .build()
        );
        assertThat(res1.matches()).isEmpty();

        // 验证 docId2 丝毫不受影响
        EmbeddingSearchResult<TextSegment> res2 = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId2))
                        .maxResults(5)
                        .build()
        );
        assertThat(res2.matches()).hasSize(1);
    }

    @Test
    @DisplayName("ingestAsync: 异步执行入库，返回 CompletableFuture 且结果正确")
    void testIngestAsync() throws Exception {
        String docId = "DOC-ASYNC-007";
        String content = "异步多线程执行 RAG 摄取管道，保障 Web 线程池不被长耗时文件解析占用。";

        CompletableFuture<IngestResult> future = ingestionTemplate.ingestAsync(
                content, "async.txt", IngestOptions.ofDocId(docId));

        IngestResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDocId()).isEqualTo(docId);
        assertThat(result.getChunkCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("HTML 超文本格式由 DocumentParserManager 自动解析并清洗标签后入库")
    void testIngestHtmlDocument() {
        String docId = "DOC-HTML-008";
        String htmlContent = "<html><body><h1>企业知识库标题</h1><p>这是段落内容，说明知识库清洗。</p></body></html>";

        ByteArrayResource resource = new ByteArrayResource(htmlContent.getBytes(StandardCharsets.UTF_8), "guide.html");
        IngestResult result = ingestionTemplate.ingest(resource, IngestOptions.ofDocId(docId, "guide.html"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChunkCount()).isGreaterThan(0);

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                        .filter(MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId))
                        .maxResults(5)
                        .build()
        );

        assertThat(searchResult.matches()).isNotEmpty();
        String text = searchResult.matches().get(0).embedded().text();
        assertThat(text).contains("企业知识库标题").contains("这是段落内容").doesNotContain("<html>").doesNotContain("<body>");
    }

    @Test
    @DisplayName("防御性边界测试：非法入参卫语句拦截")
    void testDefensiveValidation() {
        assertThatThrownBy(() -> ingestionTemplate.ingest((String) null, "test.txt"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ingestionTemplate.deleteDocument(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ingestionTemplate.deleteDocument("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
