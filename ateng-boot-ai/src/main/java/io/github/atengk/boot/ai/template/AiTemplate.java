package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;
import io.github.atengk.boot.ai.prompt.PromptTemplate;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.streaming.SseTokenStreamBridge;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.ai.util.ImageContentParser;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.RetryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

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
    private final AiTenantModelResolver tenantModelResolver;
    private final AiQuotaChecker quotaChecker;

    public AiTemplate(AiModelRegistry registry) {
        this(registry, new AtengAiProperties());
    }

    public AiTemplate(AiModelRegistry registry, AtengAiProperties properties) {
        this(registry, properties, null, null);
    }

    public AiTemplate(AiModelRegistry registry,
                      AtengAiProperties properties,
                      AiTenantModelResolver tenantModelResolver,
                      AiQuotaChecker quotaChecker) {
        this.registry = Objects.requireNonNull(registry, "AiModelRegistry 不能为空");
        this.properties = properties != null ? properties : new AtengAiProperties();
        this.tenantModelResolver = tenantModelResolver;
        this.quotaChecker = quotaChecker;
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

    /**
     * 指定模型名称进行图文多模态变长参数对话语法糖
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 变长数组
     * @return 大模型响应文本
     */
    public String chatWithModel(String modelName, String prompt, String... imageUrls) {
        List<String> list = imageUrls != null ? Arrays.asList(imageUrls) : Collections.emptyList();
        return chatWithModel(modelName, prompt, list);
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
    // SSE 打字机流式输出
    // -------------------------------------------------------------------------

    /**
     * 默认 SSE 流式通信超时时间 (3 分钟)
     */
    public static final Duration DEFAULT_SSE_TIMEOUT = Duration.ofMinutes(3);

    /**
     * 极简流式调用：自动创建并返回默认超时的 SseEmitter 实例 (使用当前上下文或默认模型)
     *
     * @param prompt 提示词
     * @return SseEmitter 实例
     */
    public SseEmitter stream(String prompt) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        stream(prompt, emitter);
        return emitter;
    }

    /**
     * 指定模型进行极简流式调用：自动创建并返回默认超时的 SseEmitter 实例
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithModel(String modelName, String prompt) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        streamWithModel(modelName, prompt, emitter);
        return emitter;
    }

    /**
     * 流式文本对话 (使用当前上下文或默认模型)
     *
     * @param prompt  提示词
     * @param emitter Spring MVC SseEmitter 实例
     */
    public void stream(String prompt, SseEmitter emitter) {
        streamWithModel(null, prompt, emitter);
    }

    /**
     * 指定模型进行流式文本对话
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamWithModel(String modelName, String prompt, SseEmitter emitter) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        if (!StringUtils.hasText(prompt)) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);
            bridge.onError(new AiException(AiResultCode.AI_INVALID_PAYLOAD, "用户提示词 prompt 不能为空"));
            return;
        }
        streamMessages(modelName, Collections.singletonList(UserMessage.from(prompt.trim())), emitter);
    }

    /**
     * 图文多模态流式对话 (使用当前上下文或默认模型)
     *
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void stream(String prompt, List<String> imageUrls, SseEmitter emitter) {
        streamWithModel(null, prompt, imageUrls, emitter);
    }

    /**
     * 图文多模态变长参数流式对话语法糖
     *
     * @param prompt    提示词
     * @param emitter   Spring MVC SseEmitter 实例（因 Java 变长参数限制置于变长图片前）
     * @param imageUrls 图片 URL 或 Base64 变长数组（Java 限制必须置于形参末尾）
     */
    public void stream(String prompt, SseEmitter emitter, String... imageUrls) {
        List<String> list = imageUrls != null ? Arrays.asList(imageUrls) : Collections.emptyList();
        streamWithModel(null, prompt, list, emitter);
    }

    /**
     * 指定模型进行图文多模态变长参数流式对话语法糖
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param emitter   Spring MVC SseEmitter 实例（因 Java 变长参数限制置于变长图片前）
     * @param imageUrls 图片 URL 或 Base64 变长数组（Java 限制必须置于形参末尾）
     */
    public void streamWithModel(String modelName, String prompt, SseEmitter emitter, String... imageUrls) {
        List<String> list = imageUrls != null ? Arrays.asList(imageUrls) : Collections.emptyList();
        streamWithModel(modelName, prompt, list, emitter);
    }

    /**
     * 指定模型进行图文多模态流式对话
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamWithModel(String modelName, String prompt, List<String> imageUrls, SseEmitter emitter) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        try {
            UserMessage userMessage = ImageContentParser.toUserMessage(prompt, imageUrls);
            streamMessages(modelName, Collections.singletonList(userMessage), emitter);
        } catch (Throwable t) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);
            bridge.onError(t);
        }
    }

    /**
     * 多轮对话消息列表流式推流 (使用当前上下文或默认模型)
     *
     * @param messages 消息历史列表
     * @param emitter  Spring MVC SseEmitter 实例
     */
    public void streamMessages(List<ChatMessage> messages, SseEmitter emitter) {
        streamMessages(null, messages, emitter);
    }

    /**
     * 指定模型进行多轮对话消息流式推流
     *
     * @param modelName 模型名称或别名
     * @param messages  消息历史列表
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamMessages(String modelName, List<ChatMessage> messages, SseEmitter emitter) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);
        try {
            stream(modelName, messages, bridge);
        } catch (Throwable t) {
            bridge.onError(t);
        }
    }

    /**
     * 底层流式响应消费接口 (使用当前上下文或默认模型)
     *
     * @param messages 消息历史列表
     * @param handler  自定义流式响应处理器
     */
    public void stream(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        stream(null, messages, handler);
    }

    /**
     * 指定模型进行底层流式响应消费
     *
     * @param modelName 模型名称或别名
     * @param messages  消息历史列表
     * @param handler   自定义流式响应处理器
     */
    public void stream(String modelName, List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        if (messages == null || messages.isEmpty()) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "对话消息列表不能为空");
        }
        if (handler == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "StreamingResponseHandler 不能为空");
        }

        String effectiveModelName = resolveEffectiveModelName(modelName);
        String tenantId = TenantContextHolder.getTenantId();

        // 1. 前置配额风控防卫 (Fail-Fast)
        if (quotaChecker != null) {
            int promptTokens = estimatePromptTokens(messages);
            quotaChecker.checkQuota(tenantId, effectiveModelName, promptTokens);
        }

        StreamingChatLanguageModel model = resolveStreamingChatModel(effectiveModelName);
        StreamingResponseHandler<AiMessage> wrappedHandler = (quotaChecker != null)
                ? new QuotaTrackingStreamingResponseHandler(handler, quotaChecker, tenantId, effectiveModelName, messages)
                : handler;
        model.generate(messages, wrappedHandler);
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

        String effectiveModelName = resolveEffectiveModelName(modelName);
        String tenantId = TenantContextHolder.getTenantId();

        // 1. 前置配额风控防卫 (Fail-Fast)
        if (quotaChecker != null) {
            int promptTokens = estimatePromptTokens(messages);
            quotaChecker.checkQuota(tenantId, effectiveModelName, promptTokens);
        }

        ChatLanguageModel model = resolveChatModel(effectiveModelName);
        AiModelConfig config = resolveModelConfig(effectiveModelName);
        int maxRetries = config.getMaxRetries() != null ? config.getMaxRetries() : 3;
        Duration initialInterval = config.getRetryInitialInterval() != null ? config.getRetryInitialInterval() : Duration.ofMillis(500);
        Duration maxInterval = config.getRetryMaxInterval() != null ? config.getRetryMaxInterval() : Duration.ofSeconds(5);
        double multiplier = config.getRetryMultiplier() != null ? config.getRetryMultiplier() : 2.0;

        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(Math.max(1, maxRetries + 1))
                .delay(initialInterval)
                .multiplier(multiplier)
                .maxDelay(maxInterval)
                .jitter(true)
                .retryPredicate(AiExceptionTranslator::isRetryable)
                .onRetry((attempt, error) -> log.warn("大模型调用遇到瞬态异常，正在发起第 {} 次退避重试: {}", attempt, error.getMessage()))
                .build();

        try {
            Response<AiMessage> response = RetryUtil.execute(() -> model.generate(messages), retryConfig);
            // 2. 调用成功后累计记录租户配额消耗
            if (quotaChecker != null) {
                int totalTokens = extractTotalTokens(messages, response);
                quotaChecker.recordUsage(tenantId, effectiveModelName, totalTokens);
            }
            return response;
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

    /**
     * 获取多租户动态模型切源解析器
     *
     * @return AiTenantModelResolver
     */
    public AiTenantModelResolver getTenantModelResolver() {
        return tenantModelResolver;
    }

    /**
     * 获取配额风控检查器
     *
     * @return AiQuotaChecker
     */
    public AiQuotaChecker getQuotaChecker() {
        return quotaChecker;
    }

    private String resolveEffectiveModelName(String modelName) {
        String target = modelName;
        if (!StringUtils.hasText(target)) {
            target = AiContextHolder.getModelName();
        }
        if (tenantModelResolver != null) {
            String tenantId = TenantContextHolder.getTenantId();
            target = tenantModelResolver.resolveModel(tenantId, target);
        }
        return target;
    }

    private ChatLanguageModel resolveChatModel(String modelName) {
        if (StringUtils.hasText(modelName)) {
            return registry.getChatModel(modelName);
        }
        return registry.getChatModel();
    }

    private StreamingChatLanguageModel resolveStreamingChatModel(String modelName) {
        if (StringUtils.hasText(modelName)) {
            return registry.getStreamingChatModel(modelName);
        }
        return registry.getStreamingChatModel();
    }

    private AiModelConfig resolveModelConfig(String modelName) {
        if (properties == null) {
            return new AiModelConfig();
        }
        return properties.resolveConfig(modelName);
    }

    private String extractText(Response<AiMessage> response) {
        if (response == null || response.content() == null) {
            return "";
        }
        return response.content().text() != null ? response.content().text() : "";
    }

    private int estimatePromptTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        String promptText = extractPromptText(messages);
        return FallbackTokenEstimator.estimateTokens(promptText);
    }

    private static String extractPromptText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .map(msg -> {
                    if (msg instanceof UserMessage um) {
                        if (um.hasSingleText()) {
                            return um.singleText();
                        }
                        if (um.contents() != null) {
                            return um.contents().stream()
                                    .filter(c -> c instanceof dev.langchain4j.data.message.TextContent)
                                    .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.joining(" "));
                        }
                        return "";
                    } else if (msg instanceof SystemMessage sm) {
                        return sm.text();
                    } else if (msg instanceof AiMessage am) {
                        return am.text();
                    }
                    return "";
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    private static int extractTotalTokens(List<ChatMessage> messages, Response<AiMessage> response) {
        if (response != null && response.tokenUsage() != null) {
            Integer total = response.tokenUsage().totalTokenCount();
            if (total != null && total > 0) {
                return total;
            }
            Integer input = response.tokenUsage().inputTokenCount();
            Integer output = response.tokenUsage().outputTokenCount();
            if (input != null || output != null) {
                return (input != null ? input : 0) + (output != null ? output : 0);
            }
        }
        String promptText = extractPromptText(messages);
        String responseText = response != null && response.content() != null && response.content().text() != null
                ? response.content().text()
                : "";
        return FallbackTokenEstimator.estimate(promptText, responseText).totalTokenCount();
    }

    /**
     * 流式调用配额追踪响应处理器包装器
     */
    private static class QuotaTrackingStreamingResponseHandler implements StreamingResponseHandler<AiMessage> {

        private final StreamingResponseHandler<AiMessage> delegate;
        private final AiQuotaChecker quotaChecker;
        private final String tenantId;
        private final String modelName;
        private final List<ChatMessage> messages;

        QuotaTrackingStreamingResponseHandler(StreamingResponseHandler<AiMessage> delegate,
                                             AiQuotaChecker quotaChecker,
                                             String tenantId,
                                             String modelName,
                                             List<ChatMessage> messages) {
            this.delegate = delegate;
            this.quotaChecker = quotaChecker;
            this.tenantId = tenantId;
            this.modelName = modelName;
            this.messages = messages;
        }

        @Override
        public void onNext(String token) {
            delegate.onNext(token);
        }

        @Override
        public void onComplete(Response<AiMessage> response) {
            try {
                int totalTokens = extractTotalTokens(messages, response);
                quotaChecker.recordUsage(tenantId, modelName, totalTokens);
            } catch (Throwable t) {
                log.warn("流式调用完成后记录配额消耗失败: {}", t.getMessage());
            }
            delegate.onComplete(response);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }
    }
}
