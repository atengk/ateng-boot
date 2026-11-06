package io.github.atengk.boot.ai.registrar;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.MemoryId;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.annotation.UserId;
import io.github.atengk.boot.ai.context.EpisodicMemoryContext;
import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFact;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 声明式长效情景记忆 (@AiAgent episodicMemory) 单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class AiAgentEpisodicMemoryTest {

    @AiAgent(episodicMemory = true, extractMemory = true)
    interface CustomerServiceAgent {
        @dev.langchain4j.service.SystemMessage("你是一个客服助理")
        String chatWithUserId(@UserId @dev.langchain4j.service.UserName String userId, @dev.langchain4j.service.UserMessage String message);

        @dev.langchain4j.service.SystemMessage("你是一个客服助理")
        String chatWithMemoryId(@MemoryId String sessionId, @dev.langchain4j.service.UserMessage String message);

        @dev.langchain4j.service.SystemMessage("你是一个客服助理")
        String chat(@dev.langchain4j.service.UserMessage String message);
    }

    @AiAgent(episodicMemory = true, extractMemory = false)
    interface ReadOnlyAgent {
        @dev.langchain4j.service.SystemMessage("你是一个只读咨询专家")
        String consult(@UserId @dev.langchain4j.service.UserName String userId, @dev.langchain4j.service.UserMessage String message);
    }

    private AiModelRegistry registry;
    private ChatLanguageModel chatModel;
    private ApplicationContext applicationContext;
    private EpisodicMemoryTemplate episodicMemoryTemplate;

    @BeforeEach
    void setUp() {
        registry = new AiModelRegistry();
        chatModel = mock(ChatLanguageModel.class);
        registry.registerDefault(chatModel, null, "default");

        applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(AiModelRegistry.class)).thenReturn(registry);

        episodicMemoryTemplate = mock(EpisodicMemoryTemplate.class);
        when(episodicMemoryTemplate.isExtractorAvailable()).thenReturn(true);
        when(episodicMemoryTemplate.extractAndRememberAsync(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        EpisodicMemoryContext.clear();
    }

    @Test
    @DisplayName("阶梯一识别：优先解析 @UserId 并将召回事实注入系统提示词")
    void testTier1UserIdResolutionAndPromptInjection() {
        // Arrange
        MemoryFact fact = MemoryFact.builder()
                .subject("user")
                .predicate("preferred_language")
                .object("Java")
                .statement("用户主要使用 Java 语言进行开发")
                .build();
        when(episodicMemoryTemplate.recall(eq("请帮我写个微服务脚手架"), eq("user-1001"), anyInt(), anyDouble()))
                .thenReturn(List.of(fact));

        when(chatModel.generate(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            SystemMessage sysMsg = messages.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> (SystemMessage) m)
                    .findFirst()
                    .orElse(null);
            assertThat(sysMsg).isNotNull();
            assertThat(sysMsg.text()).contains("[用户历史长效画像与情景记忆]");
            assertThat(sysMsg.text()).contains("用户主要使用 Java 语言进行开发");
            assertThat(sysMsg.text()).contains("你是一个客服助理");
            return Response.from(AiMessage.from("已为您规划 Java Spring Boot 脚手架方案"));
        });

        AiAgentFactoryBean<CustomerServiceAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(CustomerServiceAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setEpisodicMemory(true);
        factoryBean.setExtractMemory(true);
        factoryBean.setEpisodicMemoryTemplate(episodicMemoryTemplate);
        factoryBean.afterPropertiesSet();

        CustomerServiceAgent agent = factoryBean.getObject();

        // Act
        String response = agent.chatWithUserId("user-1001", "请帮我写个微服务脚手架");

        // Assert
        assertThat(response).contains("Java Spring Boot");
        verify(episodicMemoryTemplate).recall("请帮我写个微服务脚手架", "user-1001", 5, 0.6);
        verify(episodicMemoryTemplate).extractAndRememberAsync(
                contains("用户: 请帮我写个微服务脚手架\n助手: 已为您规划 Java Spring Boot 脚手架方案"),
                eq("user-1001"),
                any()
        );
        assertThat(EpisodicMemoryContext.getInjectedPrompt()).isNull();
    }

    @Test
    @DisplayName("阶梯二识别：无 @UserId 时解析 @MemoryId 作为用户标识")
    void testTier2MemoryIdResolution() {
        // Arrange
        when(episodicMemoryTemplate.recall(anyString(), eq("session-user-2002"), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());

        when(chatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("好的，请问有什么可以帮您？")));

        AiAgentFactoryBean<CustomerServiceAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(CustomerServiceAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setEpisodicMemory(true);
        factoryBean.setExtractMemory(true);
        factoryBean.setEpisodicMemoryTemplate(episodicMemoryTemplate);
        factoryBean.afterPropertiesSet();

        CustomerServiceAgent agent = factoryBean.getObject();

        // Act
        String response = agent.chatWithMemoryId("session-user-2002", "你好");

        // Assert
        assertThat(response).isNotBlank();
        verify(episodicMemoryTemplate).recall("你好", "session-user-2002", 5, 0.6);
        verify(episodicMemoryTemplate).extractAndRememberAsync(anyString(), eq("session-user-2002"), any());
    }

    @Test
    @DisplayName("阶梯三识别：无方法参数时回退至 UserContextHolder 上下文用户")
    void testTier3ContextHolderResolution() {
        // Arrange
        UserContextHolder.setUserId("context-user-3003");
        when(episodicMemoryTemplate.recall(anyString(), eq("context-user-3003"), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());

        when(chatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("处理完成")));

        AiAgentFactoryBean<CustomerServiceAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(CustomerServiceAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setEpisodicMemory(true);
        factoryBean.setExtractMemory(true);
        factoryBean.setEpisodicMemoryTemplate(episodicMemoryTemplate);
        factoryBean.afterPropertiesSet();

        CustomerServiceAgent agent = factoryBean.getObject();

        // Act
        String response = agent.chat("测试上下文");

        // Assert
        assertThat(response).isEqualTo("处理完成");
        verify(episodicMemoryTemplate).recall("测试上下文", "context-user-3003", 5, 0.6);
        verify(episodicMemoryTemplate).extractAndRememberAsync(anyString(), eq("context-user-3003"), any());
    }

    @Test
    @DisplayName("只读模式 (extractMemory = false)：正常注入画像但跳过异步事实提炼")
    void testReadOnlyModeSkipsFactExtraction() {
        // Arrange
        MemoryFact fact = MemoryFact.builder()
                .subject("user")
                .predicate("industry")
                .object("Finance")
                .statement("用户所在行业为金融")
                .build();
        when(episodicMemoryTemplate.recall(anyString(), eq("finance-user"), anyInt(), anyDouble()))
                .thenReturn(List.of(fact));

        when(chatModel.generate(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            SystemMessage sysMsg = messages.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> (SystemMessage) m)
                    .findFirst()
                    .orElse(null);
            assertThat(sysMsg).isNotNull();
            assertThat(sysMsg.text()).contains("用户所在行业为金融");
            assertThat(sysMsg.text()).contains("你是一个只读咨询专家");
            return Response.from(AiMessage.from("金融风控方案已准备"));
        });

        AiAgentFactoryBean<ReadOnlyAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(ReadOnlyAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setEpisodicMemory(true);
        factoryBean.setExtractMemory(false);
        factoryBean.setEpisodicMemoryTemplate(episodicMemoryTemplate);
        factoryBean.afterPropertiesSet();

        ReadOnlyAgent agent = factoryBean.getObject();

        // Act
        String response = agent.consult("finance-user", "如何做交易核对？");

        // Assert
        assertThat(response).isEqualTo("金融风控方案已准备");
        verify(episodicMemoryTemplate).recall("如何做交易核对？", "finance-user", 5, 0.6);
        // 关键断言：绝对严禁调用异步提取
        verify(episodicMemoryTemplate, never()).extractAndRememberAsync(anyString(), anyString(), any());
        assertThat(EpisodicMemoryContext.getInjectedPrompt()).isNull();
    }

    @Test
    @DisplayName("租户隔离性：异步事实提取时携带 TenantContextHolder 租户标识")
    void testTenantIsolationOnExtraction() {
        // Arrange
        TenantContextHolder.setTenantId("tenant-finance-001");
        when(episodicMemoryTemplate.recall(anyString(), eq("user-tenant"), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());

        when(chatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("回答")));

        AiAgentFactoryBean<CustomerServiceAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(CustomerServiceAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setEpisodicMemory(true);
        factoryBean.setExtractMemory(true);
        factoryBean.setEpisodicMemoryTemplate(episodicMemoryTemplate);
        factoryBean.afterPropertiesSet();

        CustomerServiceAgent agent = factoryBean.getObject();

        // Act
        agent.chatWithUserId("user-tenant", "你好");

        // Assert
        verify(episodicMemoryTemplate).extractAndRememberAsync(anyString(), eq("user-tenant"), eq("tenant-finance-001"));
    }
}
