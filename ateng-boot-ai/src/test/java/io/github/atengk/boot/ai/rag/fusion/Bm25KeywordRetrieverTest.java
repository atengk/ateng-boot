package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原生轻量 BM25 稀疏关键词检索器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("原生轻量 BM25 稀疏关键词检索器单元测试")
class Bm25KeywordRetrieverTest {

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
}
