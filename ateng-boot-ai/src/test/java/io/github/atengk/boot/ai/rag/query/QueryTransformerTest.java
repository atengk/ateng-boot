package io.github.atengk.boot.ai.rag.query;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 多轮对话查询改写器与意图消解检索器全场景单测
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("多轮对话查询改写器与意图消解检索器 (RewriteQueryRetriever) 全场景单测")
class QueryTransformerTest {

    @Test
    @DisplayName("LlmQueryTransformer: 历史只有单轮或为空时直接短路绕过 LLM 调用")
    void testLlmQueryTransformerBypassOnSingleTurn() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        LlmQueryTransformer transformer = new LlmQueryTransformer(mockModel);

        Query q1 = Query.from("请问明天天气如何？");
        Query res1 = transformer.transform(q1, Collections.emptyList());
        assertThat(res1.text()).isEqualTo(q1.text());

        Query res2 = transformer.transform(q1, List.of(UserMessage.from("你好")));
        assertThat(res2.text()).isEqualTo(q1.text());

        verifyNoInteractions(mockModel);
    }

    @Test
    @DisplayName("LlmQueryTransformer: 包含代词的多轮问答成功调用 LLM 完成指代消解改写")
    void testLlmQueryTransformerSuccess() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("华为 Mate 60 的官方售价是多少")));

        LlmQueryTransformer transformer = new LlmQueryTransformer(mockModel);

        List<ChatMessage> history = List.of(
                UserMessage.from("请介绍一下华为 Mate 60 的主要配置"),
                AiMessage.from("华为 Mate 60 搭载麒麟 9000S 处理器，支持卫星通话...")
        );

        Query query = Query.from("那它的官方售价是多少？");
        Query rewritten = transformer.transform(query, history);

        assertThat(rewritten.text()).isEqualTo("华为 Mate 60 的官方售价是多少");
        verify(mockModel, times(1)).generate(any(UserMessage.class));
    }

    @Test
    @DisplayName("LlmQueryTransformer: LLM 调用发生异常时平滑降级采用原查询")
    void testLlmQueryTransformerFallbackOnException() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any(UserMessage.class)))
                .thenThrow(new RuntimeException("LLM Gateway Timeout"));

        LlmQueryTransformer transformer = new LlmQueryTransformer(mockModel);

        List<ChatMessage> history = List.of(
                UserMessage.from("iPhone 16 参数"),
                AiMessage.from("A18 芯片...")
        );

        Query query = Query.from("它什么时候发售？");
        Query result = transformer.transform(query, history);

        assertThat(result.text()).isEqualTo("它什么时候发售？");
    }

    @Test
    @DisplayName("RewriteQueryRetriever: 自动从 Query 元数据提取多轮历史并串联下游检索")
    void testRewriteQueryRetrieverWorkflow() {
        ContentRetriever mockDelegate = mock(ContentRetriever.class);
        QueryTransformer mockTransformer = mock(QueryTransformer.class);

        List<ChatMessage> history = List.of(
                UserMessage.from("介绍 Spring Boot 3"),
                AiMessage.from("Spring Boot 3 基于 Spring 6...")
        );

        Metadata queryMeta = Metadata.from(UserMessage.from("它有什么新特性？"), "session-123", history);
        Query originalQuery = Query.from("它有什么新特性？", queryMeta);
        Query rewrittenQuery = Query.from("Spring Boot 3 有什么新特性？", queryMeta);

        when(mockTransformer.transform(originalQuery, history)).thenReturn(rewrittenQuery);
        when(mockDelegate.retrieve(rewrittenQuery)).thenReturn(List.of(Content.from("新特性一览...")));

        RewriteQueryRetriever retriever = new RewriteQueryRetriever(mockDelegate, mockTransformer);

        List<Content> contents = retriever.retrieve(originalQuery);

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).textSegment().text()).isEqualTo("新特性一览...");

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mockDelegate).retrieve(captor.capture());
        assertThat(captor.getValue().text()).isEqualTo("Spring Boot 3 有什么新特性？");
    }
}
