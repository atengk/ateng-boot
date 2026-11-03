package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.prompt.PromptTemplate;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.util.ImageContentParser;
import io.github.atengk.boot.core.util.RetryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * AI 智能体与大模型核心对称操作门面
 * 提供同步对话、视觉多模态图文输入、模板插值与指数退避重试治理
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class AiTemplate {

    private static final Logger log = LoggerFactory.getLogger(AiTemplate.class);

    private final AiModelRegistry registry;
    private final AtengAiProperties properties;

    public AiTemplate(AiModelRegistry registry) {
        this(registry, new AtengAiProperties());
    }

    public AiTemplate(AiModelRegistry registry, AtengAiProperties properties) {
        this.registry = Objects.requireNonNull(registry, "AiModelRegistry 不能为空");
        this.properties = properties != null ? properties : new AtengAiProperties();
    }

    // -------------------------------------------------------------------------
    // 同步对话 (单文本)
    // -------------------------------------------------------------------------

    /**
     * 发送单轮提示词文本并获取大模型回复正文 (使用当前上下文或默认模型)
     *
     * @param prompt 用户提示词
     * @return 大模型响应文本
     */
    public String chat(String prompt) {
        return chatWithModel(null, prompt);
    }

    /**
     * 指定模型名称发送单轮提示词文本并获取回复
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @return 大模型响应文本
     */
    public String chatWithModel(String modelName, String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "用户提示词 prompt 不能为空");
        }
        Response<AiMessage> response = generate(modelName, Collections.singletonList(UserMessage.from(prompt.trim())));
        return extractText(response);
    }

    // -------------------------------------------------------------------------
    // 视觉多模态图文输入
    // -------------------------------------------------------------------------

    /**
     * 图文多模态混合对话 (使用当前上下文或默认模型)
     *
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @return 大模型响应文本
     */
    public String chat(String prompt, List<String> imageUrls) {
        return chatWithModel(null, prompt, imageUrls);
    }

    /**
     * 图文多模态变长参数对话语法糖
     *
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 变长数组
     * @return 大模型响应文本
     */
    public String chat(String prompt, String... imageUrls) {
        List<String> list = imageUrls != null ? Arrays.asList(imageUrls) : Collections.emptyList();
        return chat(prompt, list);
    }

    /**
     * 指定模型名称进行图文多模态对话
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @return 大模型响应文本
     */
    public String chatWithModel(String modelName, String prompt, List<String> imageUrls) {
        UserMessage userMessage = ImageContentParser.toUserMessage(prompt, imageUrls);
        Response<AiMessage> response = generate(modelName, Collections.singletonList(userMessage));
        return extractText(response);
    }

    // -------------------------------------------------------------------------
    // 动态模板对话
    // -------------------------------------------------------------------------

    /**
     * 基于 PromptTemplate 动态插值对话 (使用当前上下文或默认模型)
     *
     * @param template  提示词模板
     * @param variables 变量键值对
     * @return 大模型响应文本
     */
    public String chat(PromptTemplate template, Map<String, ?> variables) {
        return chatWithModel(null, template, variables);
    }

    /**
     * 指定模型名称并基于 PromptTemplate 动态插值对话
     *
     * @param modelName 模型名称或别名
     * @param template  提示词模板
     * @param variables 变量键值对
     * @return 大模型响应文本
     */
    public String chatWithModel(String modelName, PromptTemplate template, Map<String, ?> variables) {
        if (template == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "PromptTemplate 不能为空");
        }
        String renderedPrompt = template.render(variables);
        return chatWithModel(modelName, renderedPrompt);
    }

    /**
     * 指定模型名称并基于 PromptTemplate 动态插值对话 (兼容别名)
     *
     * @param modelName 模型名称或别名
     * @param template  提示词模板
     * @param variables 变量键值对
     * @return 大模型响应文本
     */
    public String chat(String modelName, PromptTemplate template, Map<String, ?> variables) {
        return chatWithModel(modelName, template, variables);
    }

    // -------------------------------------------------------------------------
    // 多轮历史消息对话
    // -------------------------------------------------------------------------

    /**
     * 发送多轮历史对话消息列表并获取回复 (使用当前上下文或默认模型)
     *
     * @param messages 消息历史列表
     * @return 大模型响应文本
     */
    public String chat(List<ChatMessage> messages) {
        return chatMessages(null, messages);
    }

    /**
     * 发送多轮历史对话消息列表并获取回复 (使用当前上下文或默认模型)
     *
     * @param messages 消息历史列表
     * @return 大模型响应文本
     */
    public String chatMessages(List<ChatMessage> messages) {
        return chatMessages(null, messages);
    }

    /**
     * 指定模型名称发送多轮历史对话消息列表
     *
     * @param modelName 模型名称或别名
     * @param messages  消息历史列表
     * @return 大模型响应文本
     */
    public String chatMessages(String modelName, List<ChatMessage> messages) {
        Response<AiMessage> response = generate(modelName, messages);
        return extractText(response);
    }

    // -------------------------------------------------------------------------
    // 底层结构化 Response<AiMessage> 生成与退避重试执行
    // -------------------------------------------------------------------------

    /**
     * 执行消息生成并返回带元数据的 Response (使用当前上下文或默认模型)
     *
     * @param messages 消息列表
     * @return 完整 Response 结果 (含 token 使用量与模型标识)
     */
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(null, messages);
    }

    /**
     * 指定模型执行消息生成并返回带元数据的 Response
     *
     * @param modelName 模型名称或别名 (若为 null 则自动解析上下文或默认模型)
     * @param messages  消息列表
     * @return 完整 Response 结果
     */
    public Response<AiMessage> generate(String modelName, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "对话消息列表不能为空");
        }

        ChatLanguageModel model = resolveChatModel(modelName);
        int maxRetries = resolveMaxRetries(modelName);

        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(Math.max(1, maxRetries + 1))
                .delay(Duration.ofMillis(500))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(5))
                .jitter(true)
                .retryPredicate(AiExceptionTranslator::isRetryable)
                .onRetry((attempt, error) -> log.warn("大模型调用遇到瞬态异常，正在发起第 {} 次退避重试: {}", attempt, error.getMessage()))
                .build();

        try {
            return RetryUtil.execute(() -> model.generate(messages), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate(t);
        }
    }

    /**
     * 获取受管模型注册中心实例
     *
     * @return AiModelRegistry
     */
    public AiModelRegistry getRegistry() {
        return registry;
    }

    private ChatLanguageModel resolveChatModel(String modelName) {
        if (StringUtils.hasText(modelName)) {
            return registry.getChatModel(modelName);
        }
        return registry.getChatModel();
    }

    private int resolveMaxRetries(String modelName) {
        if (StringUtils.hasText(modelName) && properties.getModels().containsKey(modelName)) {
            AiModelConfig config = properties.getModels().get(modelName);
            if (config != null && config.getMaxRetries() != null) {
                return config.getMaxRetries();
            }
        }
        if (properties.getModel() != null && properties.getModel().getMaxRetries() != null) {
            return properties.getModel().getMaxRetries();
        }
        return 3;
    }

    private String extractText(Response<AiMessage> response) {
        if (response == null || response.content() == null) {
            return "";
        }
        return response.content().text() != null ? response.content().text() : "";
    }
}
