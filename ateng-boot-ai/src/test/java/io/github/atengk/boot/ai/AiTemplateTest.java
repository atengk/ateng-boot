package io.github.atengk.boot.ai;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.prompt.PromptTemplate;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.quota.DefaultAiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.ai.tenant.DefaultAiTenantModelResolver;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * AI 核心操作门面 AiTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AiTemplateTest {

    private AiModelRegistry registry;
    private AtengAiProperties properties;
    private AiTemplate aiTemplate;
    private ChatLanguageModel defaultChatModel;
    private ChatLanguageModel deepseekChatModel;
    private StreamingChatLanguageModel defaultStreamingModel;
    private StreamingChatLanguageModel deepseekStreamingModel;

    @BeforeEach
    void setUp() {
        registry = new AiModelRegistry();
        properties = new AtengAiProperties();

        AiModelConfig defaultConfig = new AiModelConfig();
        defaultConfig.setMaxRetries(2);
        properties.setModel(defaultConfig);

        AiModelConfig deepseekConfig = new AiModelConfig();
        deepseekConfig.setMaxRetries(1);
        Map<String, AiModelConfig> modelsMap = new java.util.LinkedHashMap<>();
        modelsMap.put("deepseek-r1", deepseekConfig);
        properties.setModels(modelsMap);

        defaultChatModel = Mockito.mock(ChatLanguageModel.class);
        deepseekChatModel = Mockito.mock(ChatLanguageModel.class);
        defaultStreamingModel = Mockito.mock(StreamingChatLanguageModel.class);
        deepseekStreamingModel = Mockito.mock(StreamingChatLanguageModel.class);

        registry.registerDefault(defaultChatModel, defaultStreamingModel, "gpt-4o");
        registry.register("deepseek-r1", deepseekChatModel, deepseekStreamingModel);

        aiTemplate = new AiTemplate(registry, properties);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clearAll();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("验证默认同步 chat(prompt) 基础调用")
    void testDefaultChatSuccess() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("你好，我是默认大模型！")));

        String reply = aiTemplate.chat("你好");
        assertThat(reply).isEqualTo("你好，我是默认大模型！");
        verify(defaultChatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("验证指定命名模型 chat(modelName, prompt) 调用")
    void testNamedModelChatSuccess() {
        when(deepseekChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("深度思考：推理完成。")));

        String reply = aiTemplate.chatWithModel("deepseek-r1", "求解数学难题");
        assertThat(reply).isEqualTo("深度思考：推理完成。");
        verify(deepseekChatModel, times(1)).generate(anyList());
        verify(defaultChatModel, never()).generate(anyList());
    }

    @Test
    @DisplayName("验证基于 AiContextHolder 线程级动态切源路由")
    void testDynamicRoutingViaContext() {
        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Default reply")));
        when(deepseekChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("DeepSeek reply")));

        assertThat(aiTemplate.chat("Q1")).isEqualTo("Default reply");

        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useModel("deepseek-r1")) {
            assertThat(aiTemplate.chat("Q2")).isEqualTo("DeepSeek reply");
        }

        assertThat(aiTemplate.chat("Q3")).isEqualTo("Default reply");
    }

    @Test
    @DisplayName("验证图文多模态混合对话")
    void testMultimodalChat() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("这是一张发票，总计 100 元")));

        String reply = aiTemplate.chat("请识别图片中的金额", "https://example.com/invoice.png");
        assertThat(reply).isEqualTo("这是一张发票，总计 100 元");
        verify(defaultChatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("验证指定命名模型图文多模态变长参数对话语法糖")
    void testNamedModelMultimodalChatWithVarargs() {
        when(deepseekChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("Named model vision result")));

        String reply = aiTemplate.chatWithModel("deepseek-r1", "识别图表", "https://example.com/chart.png");
        assertThat(reply).isEqualTo("Named model vision result");
        verify(deepseekChatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("验证 PromptTemplate 动态模板对话")
    void testPromptTemplateChat() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("Summary for Spring Boot 3")));

        PromptTemplate template = PromptTemplate.of("请总结 {{topic}} 的核心特性");
        String reply = aiTemplate.chat(template, Map.of("topic", "Spring Boot 3"));

        assertThat(reply).isEqualTo("Summary for Spring Boot 3");
    }

    @Test
    @DisplayName("验证网络瞬态 429 限流时指数退避重试并最终成功")
    void testRetryOnRateLimitSuccess() {
        AtomicInteger counter = new AtomicInteger(0);

        when(defaultChatModel.generate(anyList())).thenAnswer(invocation -> {
            int attempt = counter.incrementAndGet();
            if (attempt == 1) {
                // 第 1 次调用触发 429 限流
                throw new OpenAiHttpException(429, "Rate limit reached, please slow down");
            }
            // 第 2 次重试成功
            return Response.from(AiMessage.from("重试后成功返回"));
        });

        String reply = aiTemplate.chat("高并发提问");
        assertThat(reply).isEqualTo("重试后成功返回");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("验证 429 限流重试次数耗尽后抛出 AI_RATE_LIMIT_EXCEEDED 业务异常")
    void testRetryExhaustedThrowsRateLimitException() {
        when(defaultChatModel.generate(anyList()))
                .thenThrow(new OpenAiHttpException(429, "Rate limit reached permanently"));

        assertThatThrownBy(() -> aiTemplate.chat("重试耗尽测试"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> {
                    AiException aiEx = (AiException) e;
                    assertThat(aiEx.getResultCode()).isEqualTo(AiResultCode.AI_RATE_LIMIT_EXCEEDED);
                    assertThat(aiEx.getCause()).isInstanceOf(OpenAiHttpException.class);
                });

        // 默认模型配置了 maxRetries=2，共执行 3 次 (首次 + 2 次重试)
        verify(defaultChatModel, times(3)).generate(anyList());
    }

    @Test
    @DisplayName("验证 401 认证失败属于不可重试异常，立即快速失败抛出 AI_AUTH_FAILED")
    void testAuthFailedFailFastNoRetry() {
        when(defaultChatModel.generate(anyList()))
                .thenThrow(new OpenAiHttpException(401, "Invalid API key"));

        assertThatThrownBy(() -> aiTemplate.chat("无效密钥提问"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> {
                    AiException aiEx = (AiException) e;
                    assertThat(aiEx.getResultCode()).isEqualTo(AiResultCode.AI_AUTH_FAILED);
                });

        // 仅调用 1 次，不进行任何重试
        verify(defaultChatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("对抗性测试：空 prompt 或空消息列表抛出 AI_INVALID_PAYLOAD")
    void testEmptyPayloadThrows() {
        assertThatThrownBy(() -> aiTemplate.chat("   "))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));

        assertThatThrownBy(() -> aiTemplate.generate((List<ChatMessage>) null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("验证极简流式调用 stream(prompt) 返回有效 SseEmitter 实例")
    void testStreamPromptSuccess() {
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("流式输出第一段");
            handler.onComplete(Response.from(AiMessage.from("流式输出第一段")));
            return null;
        }).when(defaultStreamingModel).generate(anyList(), any());

        SseEmitter emitter = aiTemplate.stream("流式提问");
        assertThat(emitter).isNotNull();
        verify(defaultStreamingModel, times(1)).generate(anyList(), any());
    }

    @Test
    @DisplayName("验证指定命名流式模型 streamWithModel(modelName, prompt, emitter)")
    void testStreamWithNamedModelSuccess() {
        SseEmitter emitter = mock(SseEmitter.class);
        aiTemplate.streamWithModel("deepseek-r1", "求导微积分", emitter);

        verify(deepseekStreamingModel, times(1)).generate(anyList(), any());
        verify(defaultStreamingModel, never()).generate(anyList(), any());
    }

    @Test
    @DisplayName("验证图文多模态流式对话 stream(prompt, imageUrls, emitter)")
    void testStreamMultimodalSuccess() {
        SseEmitter emitter = mock(SseEmitter.class);
        aiTemplate.stream("识别建筑", List.of("https://example.com/building.jpg"), emitter);

        verify(defaultStreamingModel, times(1)).generate(anyList(), any());
    }

    @Test
    @DisplayName("验证底层自定义流式处理器 stream(messages, handler)")
    void testLowLevelStreamHandler() {
        StreamingResponseHandler<AiMessage> handler = mock(StreamingResponseHandler.class);
        List<ChatMessage> messages = List.of(UserMessage.from("底层测试"));

        aiTemplate.stream(messages, handler);
        verify(defaultStreamingModel, times(1)).generate(eq(messages), eq(handler));
    }

    @Test
    @DisplayName("流式对话对抗性测试：空 prompt 或 null SseEmitter 异常拦截")
    void testStreamInvalidPayloadDefense() {
        assertThatThrownBy(() -> aiTemplate.streamMessages("deepseek-r1", List.of(UserMessage.from("hi")), null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));

        SseEmitter emitter = mock(SseEmitter.class);
        aiTemplate.streamWithModel("deepseek-r1", "   ", emitter);
        verify(emitter, times(1)).completeWithError(any());
    }

    @Test
    @DisplayName("SaaS 多租户切源测试：根据当前租户上下文自动路由至租户专属专有模型")
    void testMultiTenantDynamicModelRouting() {
        properties.setTenantModels(Map.of("tenant-finance", "deepseek-r1"));
        DefaultAiTenantModelResolver resolver = new DefaultAiTenantModelResolver(properties);
        AiTemplate multiTenantTemplate = new AiTemplate(registry, properties, resolver, null);

        when(deepseekChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("金融模型应答")));

        // 注入租户上下文
        TenantContextHolder.setTenantId("tenant-finance");
        String reply = multiTenantTemplate.chat("查询季度财报");

        assertThat(reply).isEqualTo("金融模型应答");
        verify(deepseekChatModel, times(1)).generate(anyList());
        verify(defaultChatModel, never()).generate(anyList());
    }

    @Test
    @DisplayName("配额风控测试：当租户调用次数达到上限时，AiTemplate 立即 Fail-Fast 阻断调用")
    void testQuotaBlockingFailFast() {
        properties.getQuota().setEnabled(true);
        properties.getQuota().setDailyRequestLimit(1);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties.getQuota());
        AiTemplate quotaTemplate = new AiTemplate(registry, properties, null, checker);

        when(defaultChatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("第 1 次成功")));

        TenantContextHolder.setTenantId("tenant-quota-01");

        // 第 1 次调用成功并消耗配额
        String res1 = quotaTemplate.chat("你好");
        assertThat(res1).isEqualTo("第 1 次成功");
        assertThat(checker.getDailyRequests("tenant-quota-01")).isEqualTo(1);

        // 第 2 次调用立即被前置风控阻断
        assertThatThrownBy(() -> quotaTemplate.chat("再次提问"))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode())
                .hasMessageContaining("今日 AI 调用次数已达上限 (1/1 次)");

        // 验证底层模型未发生第 2 次无效调用 (有效防御算力账单击穿)
        verify(defaultChatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("配额风控测试：流式对话前置配额阻断与完成时累计 Token 消耗")
    void testStreamingQuotaTracking() {
        properties.getQuota().setEnabled(true);
        properties.getQuota().setDailyRequestLimit(1);
        properties.getQuota().setDailyTokenLimit(500);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties.getQuota());
        AiTemplate quotaTemplate = new AiTemplate(registry, properties, null, checker);

        TenantContextHolder.setTenantId("tenant-stream-01");

        // 模拟底层流式模型立即回调 onComplete
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onComplete(Response.from(AiMessage.from("流式回答"), new dev.langchain4j.model.output.TokenUsage(10, 20, 30)));
            return null;
        }).when(defaultStreamingModel).generate(anyList(), any());

        StreamingResponseHandler<AiMessage> mockHandler = mock(StreamingResponseHandler.class);
        quotaTemplate.stream(List.of(UserMessage.from("测试流式")), mockHandler);

        // 验证已记录调用次数 1，Token 30
        assertThat(checker.getDailyRequests("tenant-stream-01")).isEqualTo(1);
        assertThat(checker.getDailyTokens("tenant-stream-01")).isEqualTo(30);

        // 再次调用流式应被即刻阻断
        assertThatThrownBy(() -> quotaTemplate.stream(List.of(UserMessage.from("超限流式")), mockHandler))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode());
    }

    public static class TestUserVo {
        @jakarta.validation.constraints.NotBlank(message = "姓名不能为空")
        private String name;
        @jakarta.validation.constraints.Min(value = 18, message = "年龄不能小于18")
        private Integer age;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
    }

    @Test
    @DisplayName("结构化抽取：单提示词成功反序列化并完成校验")
    void testChatForObjectSuccess() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("{\"name\":\"张三\",\"age\":25}")));

        TestUserVo user = aiTemplate.chatForObject("请提取用户信息", TestUserVo.class);
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("张三");
        assertThat(user.getAge()).isEqualTo(25);
    }

    @Test
    @DisplayName("结构化抽取：Markdown 代码块自动脱壳清洗并反序列化")
    void testChatForObjectMarkdownFenceStripping() {
        String markdownReply = "```json\n{\"name\":\"李四\",\"age\":30}\n```";
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from(markdownReply)));

        TestUserVo user = aiTemplate.chatForObject("请提取李四信息", TestUserVo.class);
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("李四");
        assertThat(user.getAge()).isEqualTo(30);
    }

    @Test
    @DisplayName("结构化抽取：JSR-303 格式校验不通过时快速失败抛出 AI_RESPONSE_PARSE_ERROR")
    void testChatForObjectValidationFailure() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("{\"name\":\"\",\"age\":10}")));

        assertThatThrownBy(() -> aiTemplate.chatForObject("提取未成年且名字为空的用户", TestUserVo.class))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_RESPONSE_PARSE_ERROR.getCode())
                .hasMessageContaining("未通过 JSR-303 校验");
    }

    @Test
    @DisplayName("结构化抽取：大模型返回非法 JSON 时快速失败抛出 AI_RESPONSE_PARSE_ERROR")
    void testChatForObjectInvalidJsonFailure() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("抱歉，我不能提供 JSON。")));

        assertThatThrownBy(() -> aiTemplate.chatForObject("提取用户信息", TestUserVo.class))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_RESPONSE_PARSE_ERROR.getCode())
                .hasMessageContaining("反序列化为 [TestUserVo] 失败");
    }

    @Test
    @DisplayName("结构化抽取：基于 PromptTemplate 动态模板抽取")
    void testChatForObjectWithPromptTemplate() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("{\"name\":\"王五\",\"age\":28}")));

        PromptTemplate template = PromptTemplate.of("请分析用户 {{targetUser}} 的基本信息");
        TestUserVo user = aiTemplate.chatForObject(template, Map.of("targetUser", "王五"), TestUserVo.class);

        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("王五");
        assertThat(user.getAge()).isEqualTo(28);
    }

    @Test
    @DisplayName("结构化抽取：基于多轮历史消息 ChatMessage 列表抽取")
    void testChatForObjectWithMessages() {
        when(defaultChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("{\"name\":\"赵六\",\"age\":22}")));

        List<ChatMessage> messages = List.of(
                UserMessage.from("之前聊到的小李其实叫赵六"),
                UserMessage.from("请根据对话提取用户")
        );

        TestUserVo user = aiTemplate.chatForObject(messages, TestUserVo.class);
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("赵六");
        assertThat(user.getAge()).isEqualTo(22);
    }
}
