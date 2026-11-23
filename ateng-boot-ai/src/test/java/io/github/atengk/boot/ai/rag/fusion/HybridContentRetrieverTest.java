package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 稠密与稀疏混合知识库检索器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("稠密与稀疏混合知识库检索器单元测试")
class HybridContentRetrieverTest {

    @Test
    @DisplayName("双路正常召回时执行 RRF 融合去重与重排")
    void testHybridRetrievalNormal() {
        ContentRetriever denseRetriever = Mockito.mock(ContentRetriever.class);
        ContentRetriever sparseRetriever = Mockito.mock(ContentRetriever.class);

        Content content1 = Content.from(TextSegment.from("Java 并发编程指南"));
        Content content2 = Content.from(TextSegment.from("Redis 分布式锁规范"));
        Content content3 = Content.from(TextSegment.from("MySQL 索引优化实践"));

        when(denseRetriever.retrieve(any(Query.class))).thenReturn(List.of(content1, content2));
        when(sparseRetriever.retrieve(any(Query.class))).thenReturn(List.of(content3, content1));

        HybridContentRetriever hybridRetriever = new HybridContentRetriever(
                denseRetriever,
                sparseRetriever,
                2
        );

        List<Content> results = hybridRetriever.retrieve(Query.from("并发锁设计"));

        assertThat(results).hasSize(2);
        // content1 同时在两路命中，综合得分最高排在首位
        assertThat(results.get(0).textSegment().text()).isEqualTo("Java 并发编程指南");
    }

    @Test
    @DisplayName("单路检索异常时平滑降级使用另一路召回结果")
    void testHybridRetrievalFallbackOnException() {
        ContentRetriever denseRetriever = Mockito.mock(ContentRetriever.class);
        ContentRetriever sparseRetriever = Mockito.mock(ContentRetriever.class);

        Content content1 = Content.from(TextSegment.from("正常稠密向量召回内容"));
        when(denseRetriever.retrieve(any(Query.class))).thenReturn(List.of(content1));
        // 模拟稀疏检索器遭遇网络超时等异常
        when(sparseRetriever.retrieve(any(Query.class))).thenThrow(new RuntimeException("Elasticsearch timeout"));

        HybridContentRetriever hybridRetriever = new HybridContentRetriever(
                denseRetriever,
                sparseRetriever,
                3
        );

        List<Content> results = hybridRetriever.retrieve(Query.from("测试问题"));

        // 不抛出异常，降级保留稠密结果
        assertThat(results).hasSize(1);
        assertThat(results.get(0).textSegment().text()).isEqualTo("正常稠密向量召回内容");
    }
}
