package io.github.atengk.boot.ai;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 模型工厂单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class OpenAiModelFactoryTest {

    @Test
    @DisplayName("构建同步与流式模型成功用例")
    void testCreateModelsSuccess() {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("test-api-key");
        config.setModelName("gpt-4o-mini");
        config.setTemperature(0.5);
        config.setTopP(0.9);
        config.setMaxTokens(2048);
        config.setTimeout(Duration.ofSeconds(30));
        config.setMaxRetries(2);
        config.setLogRequests(true);
        config.setLogResponses(true);
        config.setCustomHeaders(Map.of("X-Custom-Header", "value123"));

        OpenAiChatModel chatModel = OpenAiModelFactory.createChatModel(config, Collections.emptyList());
        assertThat(chatModel).isNotNull();
        assertThat(chatModel.modelName()).isEqualTo("gpt-4o-mini");

        OpenAiStreamingChatModel streamingChatModel = OpenAiModelFactory.createStreamingChatModel(config, Collections.emptyList());
        assertThat(streamingChatModel).isNotNull();
    }

    @Test
    @DisplayName("对抗性测试：配置对象为空时抛出 AI_CONFIG_ERROR")
    void testNullConfigThrows() {
        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(null, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));

        assertThatThrownBy(() -> OpenAiModelFactory.createStreamingChatModel(null, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("对抗性测试：缺少 API Key 时抛出 AI_CONFIG_ERROR")
    void testMissingApiKeyThrows() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey("   ");

        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(config, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("对抗性测试：缺少 Base URL 时抛出 AI_CONFIG_ERROR")
    void testMissingBaseUrlThrows() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey("test-key");
        config.setBaseUrl("");

        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(config, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("对抗性测试：缺少 Model Name 时抛出 AI_CONFIG_ERROR")
    void testMissingModelNameThrows() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey("test-key");
        config.setModelName("  ");

        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(config, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }
}
