package io.github.atengk.boot.sample.ai;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.github.atengk.boot.ai.rag.fusion.Bm25KeywordRetriever;
import io.github.atengk.boot.ai.rag.fusion.HybridContentRetriever;
import io.github.atengk.boot.ai.rag.ingest.DeduplicationPolicy;
import io.github.atengk.boot.ai.rag.ingest.IngestOptions;
import io.github.atengk.boot.ai.rag.ingest.IngestResult;
import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG Ingestion 自动同步 BM25 倒排索引与跨租户隔离 E2E 集成测试 (Hybrid 模式开启)
 *
 * @author Ateng
 * @since 2026-11-22
 */
@SpringBootTest
@DisplayName("RAG Ingestion 与 BM25 混合检索增量同步及租户隔离集成测试")
class HybridBm25AutoSyncIntegrationTest {

    private static MockWebServer server;

    @Autowired
    private RagIngestionTemplate ragIngestionTemplate;

    @Autowired
    private Bm25KeywordRetriever bm25KeywordRetriever;

    @Autowired
    private ContentRetriever contentRetriever;

    @BeforeAll
    static void startServer() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.contains("/embeddings")) {
                    String body = request.getBody().readString(StandardCharsets.UTF_8);
                    boolean isArray = body.contains("\"input\":[") || body.contains("\"input\": [");
                    String dataArray = isArray
                            ? """
                              [
                                {"object": "embedding", "embedding": [0.1, 0.2, 0.3, 0.4, 0.5], "index": 0},
                                {"object": "embedding", "embedding": [0.1, 0.2, 0.3, 0.4, 0.5], "index": 1}
                              ]
                              """
                            : """
                              [
                                {"object": "embedding", "embedding": [0.1, 0.2, 0.3, 0.4, 0.5], "index": 0}
                              ]
                              """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody("""
                                    {
                                      "object": "list",
                                      "data": %s,
                                      "model": "text-embedding-3-small",
                                      "usage": {"prompt_tokens": 5, "total_tokens": 5}
                                    }
                                    """.formatted(dataArray));
                }
                return new MockResponse().setResponseCode(200).setBody("{}");
            }
        });
        server.start();
    }

    @AfterAll
    static void shutdownServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.ai.model.api-key", () -> "mock-hybrid-key");
        registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
        registry.add("ateng.boot.ai.rag.enabled", () -> "true");
        registry.add("ateng.boot.ai.rag.hybrid.enabled", () -> "true");
        registry.add("ateng.boot.ai.rag.rerank.enabled", () -> "false");
        registry.add("ateng.boot.ai.rag.query-transform.enabled", () -> "false");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        bm25KeywordRetriever.clear();
    }

    @Test
    @DisplayName("开启 hybrid.enabled=true 后文档摄取自动同步 BM25 混合检索，支持跨租户严格隔离与覆盖/删除联动")
    void testIngestAutoSyncsBm25HybridRetrievalAndTenantIsolation() {
        assertThat(contentRetriever).isInstanceOf(HybridContentRetriever.class);
        assertThat(ragIngestionTemplate.getBm25Retriever()).isSameAs(bm25KeywordRetriever);

        TenantContextHolder.setTenantId("tenant-cloud-a");
        IngestResult rA = ragIngestionTemplate.ingest(
                "天枢云原生网关限流熔断操作指南。",
                "gateway-a.txt",
                IngestOptions.builder().docId("DOC-HYBRID-A").build()
        );
        assertThat(rA.isSuccess()).isTrue();

        TenantContextHolder.setTenantId("tenant-cloud-b");
        IngestResult rB = ragIngestionTemplate.ingest(
                "鲲鹏量子密码追踪采样编排概述。",
                "tracing-b.txt",
                IngestOptions.builder().docId("DOC-HYBRID-B").build()
        );
        assertThat(rB.isSuccess()).isTrue();

        // 租户 A 使用混合检索器查询自有关键词可精准召回，且绝不越权命中租户 B 文档
        TenantContextHolder.setTenantId("tenant-cloud-a");
        List<Content> tenantAHits = contentRetriever.retrieve(Query.from("天枢云原生网关"));
        assertThat(tenantAHits).isNotEmpty();
        assertThat(tenantAHits).allSatisfy(c ->
                assertThat(c.textSegment().metadata().getString("tenant_id")).isEqualTo("tenant-cloud-a"));
        assertThat(bm25KeywordRetriever.retrieve(Query.from("鲲鹏量子密码"))).isEmpty();

        // 租户 B 删除文档后，稀疏 BM25 索引同步剔除
        ragIngestionTemplate.deleteDocument("DOC-HYBRID-B");
        TenantContextHolder.setTenantId("tenant-cloud-b");
        assertThat(bm25KeywordRetriever.retrieve(Query.from("鲲鹏量子密码"))).isEmpty();

        // 租户 A 使用 OVERWRITE 更新同 docId 内容，旧词“天枢云原生网关”应立即失效
        TenantContextHolder.setTenantId("tenant-cloud-a");
        ragIngestionTemplate.ingest(
                "新版青鸾智能路由引擎白皮书。",
                "gateway-a-v2.txt",
                IngestOptions.builder().docId("DOC-HYBRID-A").policy(DeduplicationPolicy.OVERWRITE).build()
        );
        assertThat(bm25KeywordRetriever.retrieve(Query.from("天枢云原生网关"))).isEmpty();
        assertThat(bm25KeywordRetriever.retrieve(Query.from("青鸾智能路由"))).isNotEmpty();
    }
}
