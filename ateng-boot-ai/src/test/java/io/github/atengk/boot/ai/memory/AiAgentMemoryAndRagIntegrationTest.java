package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.memory.store.InMemoryChatMemoryStore;
import io.github.atengk.boot.ai.registrar.AiAgentFactoryBean;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 智能体会话记忆隔离 @MemoryId 与 RAG 知识库检索点 ContentRetriever 综合集成测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class AiAgentMemoryAndRagIntegrationTest {

    @AiAgent
    public interface MultiSessionAgent {
        @SystemMessage("你是一个客服助理")
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    @AiAgent(contentRetriever = "financeRetriever")
    public interface FinanceRagAgent {
        @SystemMessage("你是一个金融问答助手")
        String ask(@UserMessage String question);
    }

    @Test
    @DisplayName("@MemoryId 多会话隔离：不同 sessionId 维护完全独立的对话历史")
    @SuppressWarnings("unchecked")
    void testMultiSessionIsolation() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        AiModelRegistry registry = new AiModelRegistry();
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        registry.registerDefault(chatModel, null, "default");

        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();

        when(applicationContext.getBean(AiModelRegistry.class)).thenReturn(registry);
        when(applicationContext.getBeansOfType(ChatMemoryProvider.class)).thenReturn(Map.of("chatMemoryProvider", memoryProvider));
        when(applicationContext.getBeansOfType(ContentRetriever.class)).thenReturn(Map.of());

        when(chatModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("Session1 回答1")),
                Response.from(AiMessage.from("Session2 回答1")),
                Response.from(AiMessage.from("Session1 回答2"))
        );

        AiAgentFactoryBean<MultiSessionAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(MultiSessionAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        MultiSessionAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        // 1. Session A 第一轮
        String r1 = agent.chat("session_A", "我是 Alice");
        assertThat(r1).isEqualTo("Session1 回答1");

        // 2. Session B 第一轮
        String r2 = agent.chat("session_B", "我是 Bob");
        assertThat(r2).isEqualTo("Session2 回答1");

        // 3. Session A 第二轮
        String r3 = agent.chat("session_A", "我叫什么？");
        assertThat(r3).isEqualTo("Session1 回答2");

        // 验证两个 session 的记忆互相独立（各包含 1 条 SystemMessage 与各自问答轮次）
        assertThat(store.getMessageCount("session_A")).isEqualTo(5); // System, User1, Ai1, User2, Ai2
        assertThat(store.getMessageCount("session_B")).isEqualTo(3); // System, User1, Ai1

        // 验证第三次调用发送给 LLM 的 messages 包含 Alice，但不包含 Bob
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(3)).generate(captor.capture());
        List<ChatMessage> thirdCallMessages = captor.getAllValues().get(2);
        String allText = thirdCallMessages.toString();
        assertThat(allText).contains("我是 Alice");
        assertThat(allText).doesNotContain("我是 Bob");
    }

    @Test
    @DisplayName("ContentRetriever 挂载点：智能体显式绑定检索点并注入知识增强上下文")
    @SuppressWarnings("unchecked")
    void testRagContentRetrieverIntegration() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        AiModelRegistry registry = new AiModelRegistry();
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        registry.registerDefault(chatModel, null, "default");

        ContentRetriever financeRetriever = mock(ContentRetriever.class);
        when(financeRetriever.retrieve(any(Query.class))).thenReturn(
                List.of(Content.from(TextSegment.from("2026年企业所得税优惠政策：微利企业按 20% 税率缴纳。")))
        );

        when(applicationContext.getBean(AiModelRegistry.class)).thenReturn(registry);
        when(applicationContext.getBean("financeRetriever", ContentRetriever.class)).thenReturn(financeRetriever);
        when(applicationContext.getBeansOfType(ChatMemoryProvider.class)).thenReturn(Map.of());

        when(chatModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("根据企业所得税政策，微利企业税率为 20%。"))
        );

        AiAgentFactoryBean<FinanceRagAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(FinanceRagAgent.class);
        factoryBean.setContentRetriever("financeRetriever");
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        FinanceRagAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        String answer = agent.ask("今年微利企业税率是多少？");
        assertThat(answer).contains("20%");

        // 验证 ContentRetriever 确实被调用
        verify(financeRetriever, atLeastOnce()).retrieve(any(Query.class));

        // 验证发送给 LLM 的消息中包含了从知识库检索出的文本段
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).generate(captor.capture());
        List<ChatMessage> sentMessages = captor.getValue();
        String promptContent = sentMessages.toString();
        assertThat(promptContent).contains("2026年企业所得税优惠政策");
    }
}
