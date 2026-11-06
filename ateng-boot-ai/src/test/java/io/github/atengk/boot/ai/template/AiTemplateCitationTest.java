package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.rag.citation.Citation;
import io.github.atengk.boot.ai.rag.citation.RagResponse;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.streaming.ActiveStreamRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * AiTemplate 可信 RAG 证据溯源问答与流式推流单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class AiTemplateCitationTest {

    private ChatLanguageModel chatModel;
    private StreamingChatLanguageModel streamingChatModel;
    private AiTemplate aiTemplate;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatLanguageModel.class);
        streamingChatModel = mock(StreamingChatLanguageModel.class);
        AiModelRegistry registry = new AiModelRegistry();
        registry.register("default", chatModel, streamingChatModel);

        AtengAiProperties properties = new AtengAiProperties();
        ActiveStreamRegistry streamRegistry = new ActiveStreamRegistry();

        aiTemplate = new AiTemplate(
                registry,
                properties,
                null,
                null,
                streamRegistry
        );
    }

    @Test
    @DisplayName("chatWithCitations：模型返回带引用标记时自动对齐候选切片并组装 RagResponse")
    void testChatWithCitationsSuccess() {
        TextSegment seg1 = TextSegment.from("ateng-boot 遵循纯原生零臃肿依赖设计理念",
                Metadata.from(Map.of("file_name", "architecture.md", "id", "seg-001", "score", 0.95)));
        List<TextSegment> candidates = Collections.singletonList(seg1);

        when(chatModel.generate(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ChatMessage> messages = invocation.getArgument(0);
            assertThat(messages).isNotEmpty();
            String promptText = messages.get(0) instanceof dev.langchain4j.data.message.UserMessage um
                    ? um.singleText()
                    : messages.get(0).text();
            assertThat(promptText).contains("【参考资料列表】:");
            assertThat(promptText).contains("[1] (来源: architecture.md)");
            return Response.from(AiMessage.from("ateng-boot 坚持纯原生零臃肿依赖设计理念[1]。"));
        });

        RagResponse<String> response = aiTemplate.chatWithCitations("介绍一下架构理念", candidates);

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isEqualTo("ateng-boot 坚持纯原生零臃肿依赖设计理念[1]。");
        assertThat(response.getGroundingScore()).isEqualTo(1.0);
        assertThat(response.getCitations()).hasSize(1);

        Citation citation = response.getCitations().get(0);
        assertThat(citation.getIndex()).isEqualTo(1);
        assertThat(citation.getDocName()).isEqualTo("architecture.md");
        assertThat(citation.getSegmentId()).isEqualTo("seg-001");
        assertThat(citation.getSimilarityScore()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("chatWithCitations：候选切片为空时保底返回无引用提示，不调用底层模型")
    void testChatWithCitationsEmptyCandidates() {
        RagResponse<String> response = aiTemplate.chatWithCitations("随便问问", Collections.emptyList());

        assertThat(response).isNotNull();
        assertThat(response.getResult()).contains("知识库中暂未检索到与提问相关的参考知识片段");
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getGroundingScore()).isEqualTo(0.0);

        verify(chatModel, never()).generate(anyList());
    }

    @Test
    @DisplayName("streamWithCitations：流式推流模式下在推流结束时下发 citation 事件")
    void testStreamWithCitationsSuccess() {
        TextSegment seg1 = TextSegment.from("ateng-boot 严禁持久化实体直接泄露至控制器传输层",
                Metadata.from(Map.of("file_name", "standard.md", "id", "seg-002")));
        List<TextSegment> candidates = Collections.singletonList(seg1);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("控制器规范：");
            handler.onNext("严禁实体泄露[1]。");
            handler.onComplete(Response.from(AiMessage.from("控制器规范：严禁实体泄露[1]。")));
            return null;
        }).when(streamingChatModel).generate(anyList(), any(StreamingResponseHandler.class));

        SseEmitter emitter = aiTemplate.streamWithCitations("控制器规范是什么", candidates);

        assertThat(emitter).isNotNull();
        verify(streamingChatModel, times(1)).generate(anyList(), any(StreamingResponseHandler.class));
    }

    @Test
    @DisplayName("streamWithCitations：候选切片为空时优雅推流保底提示并结束")
    void testStreamWithCitationsEmptyCandidates() {
        SseEmitter emitter = aiTemplate.streamWithCitations("空切片测试", Collections.emptyList());

        assertThat(emitter).isNotNull();
        verify(streamingChatModel, never()).generate(anyList(), any(StreamingResponseHandler.class));
    }
}
