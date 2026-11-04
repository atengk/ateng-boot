package io.github.atengk.boot.ai.registrar;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 声明式智能体工厂 Bean AiAgentFactoryBean 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AiAgentFactoryBeanTest {

    record AuditRequest(String orderId, double amount) {}

    record AuditResult(
            @NotBlank(message = "审核结论不能为空")
            String conclusion,

            @NotNull(message = "风险评分不能为空")
            @Min(value = 0, message = "风险评分不能为负数")
            Integer riskScore
    ) {}

    @AiAgent
    interface TestAuditAgent {
        @dev.langchain4j.service.SystemMessage("你是一个财务风控审计员")
        AuditResult auditOrder(AuditRequest request);

        @dev.langchain4j.service.SystemMessage("你是一个问答助手")
        String ask(@dev.langchain4j.service.UserMessage String question);

        @dev.langchain4j.service.SystemMessage("你是一个模板问答助手")
        @dev.langchain4j.service.UserMessage("请以 {{role}} 的身份解答：{{question}}")
        String askWithTemplate(@V("role") String role, @V("question") String question);
    }

    @AiAgent
    @dev.langchain4j.service.SystemMessage("你是一个企业级系统助理")
    interface TestInterfaceLevelSystemAgent {
        String answer(String question);
    }

    @AiAgent
    interface TestStreamingAgent {
        @dev.langchain4j.service.SystemMessage("你是一个流式问答助理")
        TokenStream streamAsk(@dev.langchain4j.service.UserMessage String question);
    }

    private AiModelRegistry registry;
    private ChatLanguageModel defaultChatModel;
    private ChatLanguageModel deepseekChatModel;
    private StreamingChatLanguageModel defaultStreamingModel;
    private StreamingChatLanguageModel deepseekStreamingModel;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        registry = new AiModelRegistry();
        defaultChatModel = mock(ChatLanguageModel.class);
        deepseekChatModel = mock(ChatLanguageModel.class);
        defaultStreamingModel = mock(StreamingChatLanguageModel.class);
        deepseekStreamingModel = mock(StreamingChatLanguageModel.class);

        registry.registerDefault(defaultChatModel, defaultStreamingModel, "default");
        registry.register("deepseek", deepseekChatModel, deepseekStreamingModel);

        applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(AiModelRegistry.class)).thenReturn(registry);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clear();
    }

    @Test
    @DisplayName("成功通过 AiAgentFactoryBean 生成代理实例并进行基础问答")
    void testBasicChatAgent() {
        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Spring Boot 3 是一个优秀的框架")));

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        String answer = agent.ask("介绍一下 Spring Boot 3");
        assertThat(answer).isEqualTo("Spring Boot 3 是一个优秀的框架");
    }

    @Test
    @DisplayName("支持 @SystemMessage 与 @UserMessage 模板变量 @V 动态插值")
    void testTemplateInterpolation() {
        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("作为架构师，建议采用模块化设计")));

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();
        String answer = agent.askWithTemplate("架构师", "如何设计系统？");
        assertThat(answer).isEqualTo("作为架构师，建议采用模块化设计");
    }

    @Test
    @DisplayName("端到端验证复合入参自动 JSON 序列化与 Markdown ```json 输出脱壳解析为 Record")
    void testAuditOrderStructuredOutputAndParamSanitization() {
        String markdownJsonResponse = """
                ```json
                {
                  "conclusion": "PASSED",
                  "riskScore": 12
                }
                ```
                """;
        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from(markdownJsonResponse)));

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();
        AuditResult result = agent.auditOrder(new AuditRequest("ORD-8888", 2999.0));

        // 强类型断言
        assertThat(result).isNotNull();
        assertThat(result.conclusion()).isEqualTo("PASSED");
        assertThat(result.riskScore()).isEqualTo(12);

        // 校验传递给大模型的 Prompt 包含了纯 JSON 而非原生 toString
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(defaultChatModel).generate(captor.capture());

        List messages = captor.getValue();
        boolean hasJsonPrompt = false;
        for (Object msgObj : messages) {
            dev.langchain4j.data.message.ChatMessage msg = (dev.langchain4j.data.message.ChatMessage) msgObj;
            if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                if (um.singleText().contains("{\"orderId\":\"ORD-8888\",\"amount\":2999.0}")) {
                    hasJsonPrompt = true;
                }
            }
        }
        assertThat(hasJsonPrompt).isTrue();
    }

    @Test
    @DisplayName("模型返回结构化对象未通过 JSR-303 格式校验时抛出受管业务异常")
    void testJsr303ValidationOnOutput() {
        String invalidScoreResponse = """
                ```json
                {
                  "conclusion": "REJECTED",
                  "riskScore": -5
                }
                ```
                """;
        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from(invalidScoreResponse)));

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();
        assertThatThrownBy(() -> agent.auditOrder(new AuditRequest("ORD-9999", 50.0)))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_RESPONSE_PARSE_ERROR)
                .hasMessageContaining("风险评分不能为负数");
    }

    @Test
    @DisplayName("AiContextHolder 运行时切源能正确分流到底层指定模型")
    void testDynamicModelSwitching() {
        when(deepseekChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("DeepSeek 回答")));

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();

        try (var ignored = AiContextHolder.useModel("deepseek")) {
            String answer = agent.ask("你好");
            assertThat(answer).isEqualTo("DeepSeek 回答");
            verify(deepseekChatModel).generate(anyList());
            verifyNoInteractions(defaultChatModel);
        }
    }

    @Test
    @DisplayName("完整支持接口级 @SystemMessage 声明并传递给底层模型")
    void testInterfaceLevelSystemMessage() {
        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("已收到指令")));

        AiAgentFactoryBean<TestInterfaceLevelSystemAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestInterfaceLevelSystemAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestInterfaceLevelSystemAgent agent = factoryBean.getObject();
        String result = agent.answer("系统状态");
        assertThat(result).isEqualTo("已收到指令");

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(defaultChatModel).generate(captor.capture());

        List messages = captor.getValue();
        boolean hasSystemPrompt = false;
        for (Object msgObj : messages) {
            if (msgObj instanceof dev.langchain4j.data.message.SystemMessage sm) {
                if ("你是一个企业级系统助理".equals(sm.text())) {
                    hasSystemPrompt = true;
                }
            }
        }
        assertThat(hasSystemPrompt).isTrue();
    }

    @Test
    @DisplayName("显式指定 chatMemory Bean 名称时正确绑定指定的会话记忆提供者")
    void testExplicitChatMemoryLookup() {
        dev.langchain4j.memory.chat.ChatMemoryProvider mockProvider = mock(dev.langchain4j.memory.chat.ChatMemoryProvider.class);
        when(applicationContext.getBean("customChatMemory")).thenReturn(mockProvider);

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setChatMemory("customChatMemory");
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();
        verify(applicationContext).getBean("customChatMemory");
    }

    @Test
    @DisplayName("通过 AiAgent 调用智能体时触发配额风控守卫拦截与用量记录")
    void testAiAgentQuotaCheckerInterception() {
        AiQuotaChecker mockQuotaChecker = mock(AiQuotaChecker.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AiQuotaChecker> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockQuotaChecker);
        when(applicationContext.getBeanProvider(AiQuotaChecker.class)).thenReturn(provider);

        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("回复内容")));

        AiAgentFactoryBean<TestAuditAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestAuditAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestAuditAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        io.github.atengk.boot.core.context.TenantContextHolder.setTenantId("tenant-quota-01");
        try {
            agent.ask("你好");
            verify(mockQuotaChecker).checkQuota(eq("tenant-quota-01"), anyString(), anyInt());
            verify(mockQuotaChecker).recordUsage(eq("tenant-quota-01"), anyString(), anyInt());
        } finally {
            io.github.atengk.boot.core.context.TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("声明式智能体原生支持声明返回 TokenStream 流式响应方法")
    void testStreamingAgentWithTokenStream() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("流式");
            handler.onNext("回复");
            handler.onComplete(Response.from(AiMessage.from("流式回复")));
            return null;
        }).when(defaultStreamingModel).generate(anyList(), any());

        AiAgentFactoryBean<TestStreamingAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestStreamingAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestStreamingAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        TokenStream tokenStream = agent.streamAsk("请流式回答");
        assertThat(tokenStream).isNotNull();

        StringBuilder sb = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

        tokenStream
                .onNext(sb::append)
                .onComplete(resp -> completed.set(true))
                .onError(err -> {})
                .start();

        assertThat(sb.toString()).isEqualTo("流式回复");
        assertThat(completed.get()).isTrue();
        verify(defaultStreamingModel).generate(anyList(), any());
    }

    @Test
    @DisplayName("声明式智能体流式响应支持与 AiContextHolder 线程级动态切源联动")
    void testStreamingAgentDynamicRoutingWithContext() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("DeepSeek-Stream");
            handler.onComplete(Response.from(AiMessage.from("DeepSeek-Stream")));
            return null;
        }).when(deepseekStreamingModel).generate(anyList(), any());

        AiAgentFactoryBean<TestStreamingAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(TestStreamingAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.afterPropertiesSet();

        TestStreamingAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        StringBuilder sb = new StringBuilder();
        try (var ignored = AiContextHolder.useModel("deepseek")) {
            TokenStream tokenStream = agent.streamAsk("使用推理模型流式回答");
            tokenStream
                    .onNext(sb::append)
                    .onError(err -> {})
                    .start();
        }

        assertThat(sb.toString()).isEqualTo("DeepSeek-Stream");
        verify(deepseekStreamingModel).generate(anyList(), any());
        verifyNoInteractions(defaultStreamingModel);
    }
}
