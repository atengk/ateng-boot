package io.github.atengk.boot.ai.factory;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容模型实例构建工厂
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class OpenAiModelFactory {

    private OpenAiModelFactory() {
    }

    /**
     * 构建同步阻塞型 OpenAiChatModel 实例
     *
     * @param config    模型配置属性
     * @param listeners 链路监听器列表
     * @return OpenAiChatModel 实例
     */
    public static OpenAiChatModel createChatModel(AiModelConfig config, List<ChatModelListener> listeners) {
        validateConfig(config);

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(config.getTimeout() != null ? config.getTimeout() : Duration.ofSeconds(60))
                .maxRetries(config.getMaxRetries() != null ? config.getMaxRetries() : 3)
                .logRequests(Boolean.TRUE.equals(config.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(config.getLogResponses()));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getCustomHeaders() != null && !config.getCustomHeaders().isEmpty()) {
            builder.customHeaders(config.getCustomHeaders());
        }
        if (listeners != null && !listeners.isEmpty()) {
            builder.listeners(listeners);
        }

        return builder.build();
    }

    /**
     * 构建异步流式 OpenAiStreamingChatModel 实例
     *
     * @param config    模型配置属性
     * @param listeners 链路监听器列表
     * @return OpenAiStreamingChatModel 实例
     */
    public static OpenAiStreamingChatModel createStreamingChatModel(AiModelConfig config, List<ChatModelListener> listeners) {
        validateConfig(config);

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(config.getTimeout() != null ? config.getTimeout() : Duration.ofSeconds(60))
                .logRequests(Boolean.TRUE.equals(config.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(config.getLogResponses()));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getCustomHeaders() != null && !config.getCustomHeaders().isEmpty()) {
            builder.customHeaders(config.getCustomHeaders());
        }
        if (listeners != null && !listeners.isEmpty()) {
            builder.listeners(listeners);
        }

        return builder.build();
    }

    private static void validateConfig(AiModelConfig config) {
        if (config == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型配置对象不能为空");
        }
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型 API Key 不能为空");
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型 Base URL 不能为空");
        }
        if (!StringUtils.hasText(config.getModelName())) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型 Model Name 不能为空");
        }
    }
}
