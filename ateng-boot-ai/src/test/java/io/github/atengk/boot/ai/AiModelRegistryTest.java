package io.github.atengk.boot.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 模型注册中心单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AiModelRegistryTest {

    private AiModelRegistry registry;
    private ChatLanguageModel defaultChat;
    private StreamingChatLanguageModel defaultStream;
    private ChatLanguageModel deepseekChat;
    private StreamingChatLanguageModel deepseekStream;

    @BeforeEach
    void setUp() {
        registry = new AiModelRegistry();
        defaultChat = Mockito.mock(ChatLanguageModel.class);
        defaultStream = Mockito.mock(StreamingChatLanguageModel.class);
        deepseekChat = Mockito.mock(ChatLanguageModel.class);
        deepseekStream = Mockito.mock(StreamingChatLanguageModel.class);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clearAll();
    }

    @Test
    @DisplayName("验证默认模型注册与别名别用")
    void testRegisterDefaultWithAlias() {
        registry.registerDefault(defaultChat, defaultStream, "gpt-4o");

        assertThat(registry.hasModels()).isTrue();
        assertThat(registry.hasChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)).isTrue();
        assertThat(registry.hasChatModel("gpt-4o")).isTrue();

        assertThat(registry.getChatModel()).isSameAs(defaultChat);
        assertThat(registry.getChatModel("gpt-4o")).isSameAs(defaultChat);
        assertThat(registry.getStreamingChatModel()).isSameAs(defaultStream);
        assertThat(registry.getStreamingChatModel("gpt-4o")).isSameAs(defaultStream);
    }

    @Test
    @DisplayName("验证多命名模型注册与独立查找")
    void testRegisterNamedModel() {
        registry.registerDefault(defaultChat, defaultStream, "gpt-4o");
        registry.register("deepseek-r1", deepseekChat, deepseekStream);

        assertThat(registry.hasChatModel("deepseek-r1")).isTrue();
        assertThat(registry.hasStreamingChatModel("deepseek-r1")).isTrue();

        assertThat(registry.getChatModel("deepseek-r1")).isSameAs(deepseekChat);
        assertThat(registry.getStreamingChatModel("deepseek-r1")).isSameAs(deepseekStream);
        assertThat(registry.getModelNames()).containsExactlyInAnyOrder(AiModelRegistry.DEFAULT_MODEL_NAME, "gpt-4o", "deepseek-r1");
    }

    @Test
    @DisplayName("验证基于 AiContextHolder 的线程级动态切源路由")
    void testDynamicRoutingViaContextHolder() {
        registry.registerDefault(defaultChat, defaultStream, "gpt-4o");
        registry.register("deepseek-r1", deepseekChat, deepseekStream);

        // 默认无上下文，应路由至 default
        assertThat(registry.getChatModel()).isSameAs(defaultChat);
        assertThat(registry.getStreamingChatModel()).isSameAs(defaultStream);

        // 使用上下文切换为 deepseek-r1
        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useModel("deepseek-r1")) {
            assertThat(registry.getChatModel()).isSameAs(deepseekChat);
            assertThat(registry.getStreamingChatModel()).isSameAs(deepseekStream);
        }

        // 退出后自动回滚为 default
        assertThat(registry.getChatModel()).isSameAs(defaultChat);
        assertThat(registry.getStreamingChatModel()).isSameAs(defaultStream);
    }

    @Test
    @DisplayName("对抗性测试：查找不存在的模型时抛出 AI_MODEL_NOT_FOUND")
    void testNotFoundThrowsException() {
        assertThatThrownBy(() -> registry.getChatModel("non-existent"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_MODEL_NOT_FOUND));

        assertThatThrownBy(() -> registry.getStreamingChatModel("non-existent"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_MODEL_NOT_FOUND));
    }

    @Test
    @DisplayName("对抗性测试：注册或查询时传入空或空白名称防卫")
    void testBlankNameThrows() {
        assertThatThrownBy(() -> registry.register("   ", defaultChat, defaultStream))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));

        assertThatThrownBy(() -> registry.getChatModel(null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_MODEL_NOT_FOUND));
    }

    @Test
    @DisplayName("验证模型移除与清空机制")
    void testRemoveAndClear() {
        registry.register("deepseek", deepseekChat, deepseekStream);
        assertThat(registry.hasChatModel("deepseek")).isTrue();

        registry.remove("deepseek");
        assertThat(registry.hasChatModel("deepseek")).isFalse();

        registry.registerDefault(defaultChat, defaultStream, "gpt-4o");
        assertThat(registry.hasModels()).isTrue();

        registry.clear();
        assertThat(registry.hasModels()).isFalse();
    }
}
