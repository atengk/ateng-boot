package io.github.atengk.boot.ai;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.prompt.PromptTemplate;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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

        registry.registerDefault(defaultChatModel, null, "gpt-4o");
        registry.register("deepseek-r1", deepseekChatModel, null);

        aiTemplate = new AiTemplate(registry, properties);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clearAll();
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
}
