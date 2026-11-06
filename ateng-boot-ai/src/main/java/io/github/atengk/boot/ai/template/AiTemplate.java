package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.guardrail.*;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;
import io.github.atengk.boot.ai.prompt.PromptTemplate;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AiResponseFormat;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.rag.citation.Citation;
import io.github.atengk.boot.ai.rag.citation.CitationExtractor;
import io.github.atengk.boot.ai.rag.citation.RagResponse;
import io.github.atengk.boot.ai.ratelimit.AiRateLimiter;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.sanitizer.JsonPayloadSanitizer;
import io.github.atengk.boot.ai.sanitizer.JsonSchemaGenerator;
import io.github.atengk.boot.ai.streaming.ActiveStreamRegistry;
import io.github.atengk.boot.ai.streaming.SseTokenStreamBridge;
import io.github.atengk.boot.ai.streaming.StreamContextHolder;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.ai.tool.DynamicToolChatService;
import io.github.atengk.boot.ai.tool.DynamicToolStreamService;
import io.github.atengk.boot.ai.tool.ToolExecutionSandbox;
import io.github.atengk.boot.ai.util.ImageContentParser;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecutor;

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
    private final ActiveStreamRegistry activeStreamRegistry;
    private final AiGuardrail guardrail;
    private final AiRateLimiter rateLimiter;

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
        this(registry, properties, tenantModelResolver, quotaChecker, null);
    }

    public AiTemplate(AiModelRegistry registry,
                      AtengAiProperties properties,
                      AiTenantModelResolver tenantModelResolver,
                      AiQuotaChecker quotaChecker,
                      ActiveStreamRegistry activeStreamRegistry) {
        this(registry, properties, tenantModelResolver, quotaChecker, activeStreamRegistry, null);
    }

    public AiTemplate(AiModelRegistry registry,
                      AtengAiProperties properties,
                      AiTenantModelResolver tenantModelResolver,
                      AiQuotaChecker quotaChecker,
                      ActiveStreamRegistry activeStreamRegistry,
                      AiGuardrail guardrail) {
        this(registry, properties, tenantModelResolver, quotaChecker, activeStreamRegistry, guardrail, null);
    }

    public AiTemplate(AiModelRegistry registry,
                      AtengAiProperties properties,
                      AiTenantModelResolver tenantModelResolver,
                      AiQuotaChecker quotaChecker,
                      ActiveStreamRegistry activeStreamRegistry,
                      AiGuardrail guardrail,
                      AiRateLimiter rateLimiter) {
        this.registry = Objects.requireNonNull(registry, "AiModelRegistry 不能为空");
        this.properties = properties != null ? properties : new AtengAiProperties();
        this.tenantModelResolver = tenantModelResolver;
        this.quotaChecker = quotaChecker;
        this.activeStreamRegistry = activeStreamRegistry;
        this.guardrail = guardrail != null ? guardrail : new DefaultAiGuardrail(this.properties.getGuardrail());
        this.rateLimiter = rateLimiter;
    }

    /**
     * 获取 AI 微观突发流量限流器实例
     *
     * @return AiRateLimiter 实例，若未启用则可能为 null
     */
    public AiRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    /**
     * 获取 AI 双向内容安全护栏实例
     *
     * @return AiGuardrail 实例
     */
    public AiGuardrail getGuardrail() {
        return guardrail;
    }

    /**
     * 获取活跃流式连接并发注册表
     *
     * @return ActiveStreamRegistry 实例，若未启用则可能为 null
     */
    public ActiveStreamRegistry getActiveStreamRegistry() {
        return activeStreamRegistry;
    }

    /**
     * 根据 streamId 主动物理切断指定流式推流连接并排空资源
     *
     * @param streamId 流式会话唯一标识
     * @return 若找到活跃连接并触发取消成功返回 true，否则返回 false
     */
    public boolean cancelStream(String streamId) {
        if (activeStreamRegistry != null && StringUtils.hasText(streamId)) {
            return activeStreamRegistry.cancel(streamId.trim());
        }
        return false;
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
    // 强类型结构化对象提取 (chatForObject)
    // -------------------------------------------------------------------------

    private static final String JSON_OUTPUT_GUIDANCE = "\n\n【输出格式要求】：请直接以合法 JSON 格式输出，切勿输出任何与 JSON 无关的解释性对话文字。";

    /**
     * 单提示词结构化抽取：自动注入 JSON 格式要求、脱壳清洗 Markdown 标记并进行 JSR-303 防卫校验 (使用默认/当前上下文模型)
     *
     * @param prompt       提示词文本
     * @param responseType 目标反序列化类型 Class
     * @param <T>          泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String prompt, Class<T> responseType) {
        return chatForObject(null, prompt, responseType);
    }

    /**
     * 单提示词结构化抽取 (带 JSR-303 分组校验)
     *
     * @param prompt           提示词文本
     * @param responseType     目标反序列化类型 Class
     * @param validationGroups JSR-303 分组
     * @param <T>              泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String prompt, Class<T> responseType, Class<?>... validationGroups) {
        return chatForObject(null, prompt, responseType, validationGroups);
    }

    /**
     * 指定模型进行单提示词结构化抽取
     *
     * @param modelName    模型名称或别名
     * @param prompt       提示词文本
     * @param responseType 目标反序列化类型 Class
     * @param <T>          泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String modelName, String prompt, Class<T> responseType) {
        return chatForObject(modelName, prompt, responseType, new Class<?>[0]);
    }

    /**
     * 指定模型进行单提示词结构化抽取 (带 JSR-303 分组校验)
     *
     * @param modelName        模型名称或别名
     * @param prompt           提示词文本
     * @param responseType     目标反序列化类型 Class
     * @param validationGroups JSR-303 分组
     * @param <T>              泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String modelName, String prompt, Class<T> responseType, Class<?>... validationGroups) {
        Objects.requireNonNull(responseType, "目标反序列化类型 responseType 不能为空");
        AiModelConfig modelConfig = properties.resolveConfig(modelName);
        String guidedPrompt;
        if (modelConfig.getResponseFormat() == AiResponseFormat.JSON_SCHEMA) {
            String schema = JsonSchemaGenerator.generateSchema(responseType);
            guidedPrompt = appendJsonSchemaGuidance(prompt, schema);
        } else {
            guidedPrompt = appendJsonGuidance(prompt);
        }
        String rawReply = chatWithModel(modelName, guidedPrompt);
        return JsonPayloadSanitizer.parseAndValidate(rawReply, responseType, validationGroups);
    }

    /**
     * 基于 PromptTemplate 动态插值结构化抽取 (使用默认/当前上下文模型)
     *
     * @param template     提示词模板
     * @param variables    变量键值映射
     * @param responseType 目标反序列化类型 Class
     * @param <T>          泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(PromptTemplate template, Map<String, ?> variables, Class<T> responseType) {
        return chatForObject(null, template, variables, responseType, new Class<?>[0]);
    }

    /**
     * 指定模型并基于 PromptTemplate 动态插值结构化抽取 (带 JSR-303 分组校验)
     *
     * @param modelName        模型名称或别名
     * @param template         提示词模板
     * @param variables        变量键值映射
     * @param responseType     目标反序列化类型 Class
     * @param validationGroups JSR-303 分组
     * @param <T>              泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String modelName, PromptTemplate template, Map<String, ?> variables, Class<T> responseType, Class<?>... validationGroups) {
        if (template == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "PromptTemplate 不能为空");
        }
        String renderedPrompt = template.render(variables);
        return chatForObject(modelName, renderedPrompt, responseType, validationGroups);
    }

    /**
     * 图文多模态结构化抽取 (使用默认/当前上下文模型)
     *
     * @param prompt       提示词文本
     * @param imageUrls    图片 URL 或 Base64 列表
     * @param responseType 目标反序列化类型 Class
     * @param <T>          泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String prompt, List<String> imageUrls, Class<T> responseType) {
        return chatForObject(null, prompt, imageUrls, responseType, new Class<?>[0]);
    }

    /**
     * 指定模型进行图文多模态结构化抽取
     *
     * @param modelName        模型名称或别名
     * @param prompt           提示词文本
     * @param imageUrls        图片 URL 或 Base64 列表
     * @param responseType     目标反序列化类型 Class
     * @param validationGroups JSR-303 分组
     * @param <T>              泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String modelName, String prompt, List<String> imageUrls, Class<T> responseType, Class<?>... validationGroups) {
        Objects.requireNonNull(responseType, "目标反序列化类型 responseType 不能为空");
        String guidedPrompt = appendJsonGuidance(prompt);
        String rawReply = chatWithModel(modelName, guidedPrompt, imageUrls);
        return JsonPayloadSanitizer.parseAndValidate(rawReply, responseType, validationGroups);
    }

    /**
     * 多轮对话消息列表结构化抽取 (使用默认/当前上下文模型)
     *
     * @param messages     消息历史列表
     * @param responseType 目标反序列化类型 Class
     * @param <T>          泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(List<ChatMessage> messages, Class<T> responseType) {
        return chatForObject(null, messages, responseType, new Class<?>[0]);
    }

    /**
     * 指定模型进行多轮对话消息列表结构化抽取
     *
     * @param modelName        模型名称或别名
     * @param messages         消息历史列表
     * @param responseType     目标反序列化类型 Class
     * @param validationGroups JSR-303 分组
     * @param <T>              泛型类型
     * @return 校验通过的结构化对象实例
     */
    public <T> T chatForObject(String modelName, List<ChatMessage> messages, Class<T> responseType, Class<?>... validationGroups) {
        Objects.requireNonNull(responseType, "目标反序列化类型 responseType 不能为空");
        AiModelConfig modelConfig = properties.resolveConfig(modelName);
        List<ChatMessage> guidedMessages;
        if (modelConfig.getResponseFormat() == AiResponseFormat.JSON_SCHEMA) {
            String schema = JsonSchemaGenerator.generateSchema(responseType);
            guidedMessages = appendJsonSchemaGuidance(messages, schema);
        } else {
            guidedMessages = appendJsonGuidance(messages);
        }
        String rawReply = chatMessages(modelName, guidedMessages);
        return JsonPayloadSanitizer.parseAndValidate(rawReply, responseType, validationGroups);
    }

    private String appendJsonGuidance(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return JSON_OUTPUT_GUIDANCE.trim();
        }
        return prompt.trim() + JSON_OUTPUT_GUIDANCE;
    }

    private String appendJsonSchemaGuidance(String prompt, String jsonSchema) {
        String guidance = "\n\n【输出格式要求】：请严格遵守以下 JSON Schema 规范输出合法 JSON，切勿输出任何与 JSON 无关的解释性对话文字：\n" + jsonSchema;
        if (!StringUtils.hasText(prompt)) {
            return guidance.trim();
        }
        return prompt.trim() + guidance;
    }

    private List<ChatMessage> appendJsonGuidance(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.singletonList(UserMessage.from(JSON_OUTPUT_GUIDANCE.trim()));
        }
        List<ChatMessage> list = new ArrayList<>(messages);
        int lastIndex = list.size() - 1;
        ChatMessage last = list.get(lastIndex);
        if (last instanceof UserMessage userMessage) {
            String originalText = FallbackTokenEstimator.extractPromptText(Collections.singletonList(userMessage));
            list.set(lastIndex, UserMessage.from(appendJsonGuidance(originalText)));
        } else {
            list.add(UserMessage.from(JSON_OUTPUT_GUIDANCE.trim()));
        }
        return list;
    }

    private List<ChatMessage> appendJsonSchemaGuidance(List<ChatMessage> messages, String jsonSchema) {
        String guidance = "\n\n【输出格式要求】：请严格遵守以下 JSON Schema 规范输出合法 JSON，切勿输出任何与 JSON 无关的解释性对话文字：\n" + jsonSchema;
        if (messages == null || messages.isEmpty()) {
            return Collections.singletonList(UserMessage.from(guidance.trim()));
        }
        List<ChatMessage> list = new ArrayList<>(messages);
        int lastIndex = list.size() - 1;
        ChatMessage last = list.get(lastIndex);
        if (last instanceof UserMessage userMessage) {
            String originalText = FallbackTokenEstimator.extractPromptText(Collections.singletonList(userMessage));
            list.set(lastIndex, UserMessage.from(originalText.trim() + guidance));
        } else {
            list.add(UserMessage.from(guidance.trim()));
        }
        return list;
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
        streamWithModel(null, prompt, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 流式文本对话 (使用当前上下文或默认模型并绑定指定 streamId)
     *
     * @param prompt   提示词
     * @param emitter  Spring MVC SseEmitter 实例
     * @param streamId 流式唯一标识
     */
    public void stream(String prompt, SseEmitter emitter, String streamId) {
        streamWithModel(null, prompt, emitter, streamId);
    }

    /**
     * 指定模型进行流式文本对话
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamWithModel(String modelName, String prompt, SseEmitter emitter) {
        streamWithModel(modelName, prompt, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型进行流式文本对话并绑定指定 streamId
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param emitter   Spring MVC SseEmitter 实例
     * @param streamId  流式唯一标识
     */
    public void streamWithModel(String modelName, String prompt, SseEmitter emitter, String streamId) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        String effectiveStreamId = StringUtils.hasText(streamId) ? streamId.trim() : IdUtil.fastSimpleUUID();
        if (!StringUtils.hasText(prompt)) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
            bridge.onError(new AiException(AiResultCode.AI_INVALID_PAYLOAD, "用户提示词 prompt 不能为空"));
            return;
        }
        streamMessages(modelName, Collections.singletonList(UserMessage.from(prompt.trim())), emitter, effectiveStreamId);
    }

    /**
     * 图文多模态流式对话 (使用当前上下文或默认模型)
     *
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void stream(String prompt, List<String> imageUrls, SseEmitter emitter) {
        streamWithModel(null, prompt, imageUrls, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 图文多模态流式对话 (使用当前上下文或默认模型并绑定指定 streamId)
     *
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @param emitter   Spring MVC SseEmitter 实例
     * @param streamId  流式唯一标识
     */
    public void stream(String prompt, List<String> imageUrls, SseEmitter emitter, String streamId) {
        streamWithModel(null, prompt, imageUrls, emitter, streamId);
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
        streamWithModel(null, prompt, list, emitter, IdUtil.fastSimpleUUID());
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
        streamWithModel(modelName, prompt, list, emitter, IdUtil.fastSimpleUUID());
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
        streamWithModel(modelName, prompt, imageUrls, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型进行图文多模态流式对话并绑定指定 streamId
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param imageUrls 图片 URL 或 Base64 编码列表
     * @param emitter   Spring MVC SseEmitter 实例
     * @param streamId  流式唯一标识
     */
    public void streamWithModel(String modelName, String prompt, List<String> imageUrls, SseEmitter emitter, String streamId) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        String effectiveStreamId = StringUtils.hasText(streamId) ? streamId.trim() : IdUtil.fastSimpleUUID();
        try {
            UserMessage userMessage = ImageContentParser.toUserMessage(prompt, imageUrls);
            streamMessages(modelName, Collections.singletonList(userMessage), emitter, effectiveStreamId);
        } catch (Throwable t) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
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
        streamMessages(null, messages, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 多轮对话消息列表流式推流 (使用当前上下文或默认模型并绑定指定 streamId)
     *
     * @param messages 消息历史列表
     * @param emitter  Spring MVC SseEmitter 实例
     * @param streamId 流式唯一标识
     */
    public void streamMessages(List<ChatMessage> messages, SseEmitter emitter, String streamId) {
        streamMessages(null, messages, emitter, streamId);
    }

    /**
     * 指定模型进行多轮对话消息流式推流
     *
     * @param modelName 模型名称或别名
     * @param messages  消息历史列表
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamMessages(String modelName, List<ChatMessage> messages, SseEmitter emitter) {
        streamMessages(modelName, messages, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型进行多轮对话消息流式推流并绑定指定 streamId
     *
     * @param modelName 模型名称或别名
     * @param messages  消息历史列表
     * @param emitter   Spring MVC SseEmitter 实例
     * @param streamId  流式唯一标识
     */
    public void streamMessages(String modelName, List<ChatMessage> messages, SseEmitter emitter, String streamId) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        String effectiveStreamId = StringUtils.hasText(streamId) ? streamId.trim() : IdUtil.fastSimpleUUID();
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
        try {
            StreamContextHolder.setStreamId(effectiveStreamId);
            try {
                stream(modelName, messages, bridge);
            } finally {
                StreamContextHolder.clear();
            }
        } catch (Throwable t) {
            bridge.onError(t);
        }
    }

    // -------------------------------------------------------------------------
    // 编程式动态工具与 MCP 客户端对话 (chatWithTools / streamWithTools)
    // -------------------------------------------------------------------------

    /**
     * 携带动态工具实例列表发起同步对话 (使用当前上下文或默认模型)
     *
     * @param prompt 用户提示词
     * @param tools  动态工具对象列表 (支持标注 @Tool 的 POJO、McpClientAdapter、McpClientRegistry 或工具映射)
     * @return 大模型响应文本
     */
    public String chatWithTools(String prompt, List<Object> tools) {
        return chatWithTools(null, prompt, tools);
    }

    /**
     * 携带动态工具实例列表发起同步对话 (变长参数便捷重载)
     *
     * @param prompt 用户提示词
     * @param tools  动态工具对象数组
     * @return 大模型响应文本
     */
    public String chatWithTools(String prompt, Object... tools) {
        List<Object> list = tools != null ? Arrays.asList(tools) : Collections.emptyList();
        return chatWithTools(null, prompt, list);
    }

    /**
     * 指定模型名称并携带动态工具实例列表发起同步对话 (变长参数便捷重载)
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @param tools     动态工具对象数组
     * @return 大模型响应文本
     */
    public String chatWithTools(String modelName, String prompt, Object... tools) {
        List<Object> list = tools != null ? Arrays.asList(tools) : Collections.emptyList();
        return chatWithTools(modelName, prompt, list);
    }

    /**
     * 指定模型名称并携带动态工具实例列表发起同步对话
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @param tools     动态工具对象列表
     * @return 大模型响应文本
     */
    public String chatWithTools(String modelName, String prompt, List<Object> tools) {
        if (!StringUtils.hasText(prompt)) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "用户提示词 prompt 不能为空");
        }

        // 0. 前置内容安全护栏检查
        if (guardrail != null) {
            GuardrailResult preResult = guardrail.inspectPrompt(prompt.trim());
            if (preResult.getAction() == GuardrailAction.BLOCK) {
                throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason());
            }
            if (preResult.getAction() == GuardrailAction.REPLACE) {
                return preResult.getSafeContent();
            }
        }

        String effectiveModelName = resolveEffectiveModelName(modelName);
        String tenantId = TenantContextHolder.getTenantId();

        // 1. 前置配额与微观突发限流风控防卫 (Fail-Fast)
        int promptTokens = estimatePromptTokens(Collections.singletonList(UserMessage.from(prompt.trim())));
        if (rateLimiter != null) {
            rateLimiter.acquire(tenantId, effectiveModelName, promptTokens);
        }
        if (quotaChecker != null) {
            quotaChecker.checkQuota(tenantId, effectiveModelName, promptTokens);
        }

        try {
            ChatLanguageModel model = resolveChatModel(effectiveModelName);
            Map<ToolSpecification, ToolExecutor> sandboxedTools = ToolExecutionSandbox.wrapToolBeans(tools);

            // 2. 构造 LangChain4j AiServices 局部代理并调用
            AiServices<DynamicToolChatService> builder =
                    AiServices.builder(DynamicToolChatService.class)
                            .chatLanguageModel(model);
            if (sandboxedTools != null && !sandboxedTools.isEmpty()) {
                builder.tools(sandboxedTools);
            }

            DynamicToolChatService service = builder.build();
            String responseText = service.chat(prompt.trim());

            // 3. 统计并累计记录配额消耗
            if (quotaChecker != null) {
                int totalTokens = promptTokens + (StringUtils.hasText(responseText)
                        ? estimatePromptTokens(Collections.singletonList(UserMessage.from(responseText)))
                        : 0);
                quotaChecker.recordUsage(tenantId, effectiveModelName, totalTokens);
            }

            // 4. 后置内容安全护栏检查
            if (guardrail != null && StringUtils.hasText(responseText)) {
                GuardrailResult postResult = guardrail.inspectResponse(responseText);
                if (postResult.getAction() == GuardrailAction.BLOCK) {
                    throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, postResult.getReason());
                }
                if (postResult.getAction() == GuardrailAction.REPLACE) {
                    return postResult.getSafeContent();
                }
            }

            return responseText;
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate(t);
        }
    }

    /**
     * 携带动态工具实例列表发起流式对话并自动创建返回 SseEmitter (使用当前上下文或默认模型)
     *
     * @param prompt 用户提示词
     * @param tools  动态工具对象列表
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithTools(String prompt, List<Object> tools) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        streamWithTools(prompt, tools, emitter);
        return emitter;
    }

    /**
     * 指定模型并携带动态工具实例列表发起流式对话并自动创建返回 SseEmitter
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @param tools     动态工具对象列表
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithTools(String modelName, String prompt, List<Object> tools) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        streamWithTools(modelName, prompt, tools, emitter);
        return emitter;
    }

    /**
     * 携带动态工具实例列表发起流式推流 (使用当前上下文或默认模型)
     *
     * @param prompt  用户提示词
     * @param tools   动态工具对象列表
     * @param emitter Spring MVC SseEmitter 实例
     */
    public void streamWithTools(String prompt, List<Object> tools, SseEmitter emitter) {
        streamWithTools(null, prompt, tools, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 携带动态工具实例列表发起流式推流并绑定指定 streamId
     *
     * @param prompt   用户提示词
     * @param tools    动态工具对象列表
     * @param emitter  Spring MVC SseEmitter 实例
     * @param streamId 流式唯一标识
     */
    public void streamWithTools(String prompt, List<Object> tools, SseEmitter emitter, String streamId) {
        streamWithTools(null, prompt, tools, emitter, streamId);
    }

    /**
     * 指定模型并携带动态工具实例列表发起流式推流
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @param tools     动态工具对象列表
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamWithTools(String modelName, String prompt, List<Object> tools, SseEmitter emitter) {
        streamWithTools(modelName, prompt, tools, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 携带动态工具实例变长参数发起流式推流 (变长语法糖)
     *
     * @param prompt  用户提示词
     * @param emitter Spring MVC SseEmitter 实例
     * @param tools   动态工具对象数组
     */
    public void streamWithTools(String prompt, SseEmitter emitter, Object... tools) {
        List<Object> list = tools != null ? Arrays.asList(tools) : Collections.emptyList();
        streamWithTools(null, prompt, list, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型并携带动态工具实例变长参数发起流式推流 (变长语法糖)
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @param emitter   Spring MVC SseEmitter 实例
     * @param tools     动态工具对象数组
     */
    public void streamWithTools(String modelName, String prompt, SseEmitter emitter, Object... tools) {
        List<Object> list = tools != null ? Arrays.asList(tools) : Collections.emptyList();
        streamWithTools(modelName, prompt, list, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型并携带动态工具实例列表发起流式推流并绑定指定 streamId
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提示词
     * @param tools     动态工具对象列表
     * @param emitter   Spring MVC SseEmitter 实例
     * @param streamId  流式唯一标识
     */
    public void streamWithTools(String modelName, String prompt, List<Object> tools, SseEmitter emitter, String streamId) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        String effectiveStreamId = StringUtils.hasText(streamId) ? streamId.trim() : IdUtil.fastSimpleUUID();
        if (!StringUtils.hasText(prompt)) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
            bridge.onError(new AiException(AiResultCode.AI_INVALID_PAYLOAD, "用户提示词 prompt 不能为空"));
            return;
        }

        // 0. 前置内容安全护栏检查
        if (guardrail != null) {
            GuardrailResult preResult = guardrail.inspectPrompt(prompt.trim());
            if (preResult.getAction() == GuardrailAction.BLOCK) {
                SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
                bridge.onError(new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason()));
                return;
            }
        }

        String effectiveModelName = resolveEffectiveModelName(modelName);
        String tenantId = TenantContextHolder.getTenantId();

        // 1. 前置配额与微观突发限流防卫
        int promptTokens = estimatePromptTokens(Collections.singletonList(UserMessage.from(prompt.trim())));
        if (rateLimiter != null) {
            rateLimiter.acquire(tenantId, effectiveModelName, promptTokens);
        }
        if (quotaChecker != null) {
            quotaChecker.checkQuota(tenantId, effectiveModelName, promptTokens);
        }

        try {
            StreamingChatLanguageModel streamingModel = resolveStreamingChatModel(effectiveModelName);
            Map<ToolSpecification, ToolExecutor> sandboxedTools = ToolExecutionSandbox.wrapToolBeans(tools);

            // 2. 构造 LangChain4j 流式 AiServices 代理
            AiServices<DynamicToolStreamService> builder =
                    AiServices.builder(DynamicToolStreamService.class)
                            .streamingChatLanguageModel(streamingModel);
            if (sandboxedTools != null && !sandboxedTools.isEmpty()) {
                builder.tools(sandboxedTools);
            }

            DynamicToolStreamService service = builder.build();
            TokenStream tokenStream = service.stream(prompt.trim());

            // 3. 将 TokenStream 管道接入 SseTokenStreamBridge
            SseTokenStreamBridge.pipe(tokenStream, emitter, effectiveStreamId, activeStreamRegistry);
        } catch (Throwable t) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
            bridge.onError(AiExceptionTranslator.translate(t));
        }
    }

    // -------------------------------------------------------------------------
    // 可信 RAG 证据溯源问答 (chatWithCitations / streamWithCitations)
    // -------------------------------------------------------------------------

    /**
     * 结合知识库候选切片发起可信 RAG 问答，返回带结构化引用溯源的强类型 RagResponse (使用当前上下文或默认模型)
     *
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @return 包含生成回答、有效引用清单与忠实度评分的 RagResponse
     */
    public RagResponse<String> chatWithCitations(String prompt, List<TextSegment> candidates) {
        return chatWithCitations(null, prompt, candidates);
    }

    /**
     * 指定模型结合知识库候选切片发起可信 RAG 问答，返回带结构化引用溯源的强类型 RagResponse
     *
     * @param modelName  模型名称或别名
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @return 包含生成回答、有效引用清单与忠实度评分的 RagResponse
     */
    public RagResponse<String> chatWithCitations(String modelName, String prompt, List<TextSegment> candidates) {
        if (!StringUtils.hasText(prompt)) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "问答提问 prompt 不能为空");
        }
        List<TextSegment> safeCandidates = candidates != null ? candidates : Collections.emptyList();
        if (safeCandidates.isEmpty()) {
            return RagResponse.of("知识库中暂未检索到与提问相关的参考知识片段。", Collections.emptyList(), 0.0);
        }
        String augmentedPrompt = CitationExtractor.buildPrompt(prompt.trim(), safeCandidates);
        String rawAnswer = chatWithModel(modelName, augmentedPrompt);
        return CitationExtractor.extract(rawAnswer, safeCandidates);
    }

    /**
     * 结合 ContentRetriever 知识检索器发起可信 RAG 问答 (自动检索并对齐引用，使用当前上下文或默认模型)
     *
     * @param prompt    用户提问提示词
     * @param retriever 知识库检索器
     * @return 包含生成回答、有效引用清单与忠实度评分的 RagResponse
     */
    public RagResponse<String> chatWithCitations(String prompt, ContentRetriever retriever) {
        return chatWithCitations(null, prompt, retriever);
    }

    /**
     * 指定模型结合 ContentRetriever 知识检索器发起可信 RAG 问答 (自动检索并对齐引用)
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提问提示词
     * @param retriever 知识库检索器
     * @return 包含生成回答、有效引用清单与忠实度评分的 RagResponse
     */
    public RagResponse<String> chatWithCitations(String modelName, String prompt, ContentRetriever retriever) {
        if (!StringUtils.hasText(prompt)) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "问答提问 prompt 不能为空");
        }
        List<TextSegment> candidates = Collections.emptyList();
        if (retriever != null) {
            List<Content> contents = retriever.retrieve(Query.from(prompt.trim()));
            candidates = CitationExtractor.fromContents(contents);
        }
        return chatWithCitations(modelName, prompt, candidates);
    }

    /**
     * 结合知识库候选切片发起可信 RAG 流式问答，自动创建并返回 SseEmitter (使用当前上下文或默认模型)
     *
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithCitations(String prompt, List<TextSegment> candidates) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        streamWithCitations(null, prompt, candidates, emitter);
        return emitter;
    }

    /**
     * 指定模型结合知识库候选切片发起可信 RAG 流式问答，自动创建并返回 SseEmitter
     *
     * @param modelName  模型名称或别名
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithCitations(String modelName, String prompt, List<TextSegment> candidates) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        streamWithCitations(modelName, prompt, candidates, emitter);
        return emitter;
    }

    /**
     * 结合知识库候选切片发起可信 RAG 流式问答 (使用当前上下文或默认模型)
     *
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @param emitter    Spring MVC SseEmitter 实例
     */
    public void streamWithCitations(String prompt, List<TextSegment> candidates, SseEmitter emitter) {
        streamWithCitations(null, prompt, candidates, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型结合知识库候选切片发起可信 RAG 流式问答
     *
     * @param modelName  模型名称或别名
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @param emitter    Spring MVC SseEmitter 实例
     */
    public void streamWithCitations(String modelName, String prompt, List<TextSegment> candidates, SseEmitter emitter) {
        streamWithCitations(modelName, prompt, candidates, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 指定模型结合知识库候选切片发起可信 RAG 流式问答并绑定指定 streamId
     *
     * @param modelName  模型名称或别名
     * @param prompt     用户提问提示词
     * @param candidates 知识库候选切片列表
     * @param emitter    Spring MVC SseEmitter 实例
     * @param streamId   流式唯一标识
     */
    public void streamWithCitations(String modelName, String prompt, List<TextSegment> candidates, SseEmitter emitter, String streamId) {
        if (emitter == null) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "SseEmitter 不能为空");
        }
        String effectiveStreamId = StringUtils.hasText(streamId) ? streamId.trim() : IdUtil.fastSimpleUUID();
        if (!StringUtils.hasText(prompt)) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry);
            bridge.onError(new AiException(AiResultCode.AI_INVALID_PAYLOAD, "问答提问 prompt 不能为空"));
            return;
        }
        List<TextSegment> safeCandidates = candidates != null ? candidates : Collections.emptyList();
        if (safeCandidates.isEmpty()) {
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry)
                    .withCitationCandidates(Collections.emptyList());
            bridge.onNext("知识库中暂未检索到与提问相关的参考知识片段。");
            bridge.onComplete(null);
            return;
        }
        String augmentedPrompt = CitationExtractor.buildPrompt(prompt.trim(), safeCandidates);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, effectiveStreamId, activeStreamRegistry)
                .withCitationCandidates(safeCandidates);
        try {
            StreamContextHolder.setStreamId(effectiveStreamId);
            try {
                stream(modelName, Collections.singletonList(UserMessage.from(augmentedPrompt)), bridge);
            } finally {
                StreamContextHolder.clear();
            }
        } catch (Throwable t) {
            bridge.onError(t);
        }
    }

    /**
     * 结合 ContentRetriever 知识检索器发起可信 RAG 流式问答，自动创建并返回 SseEmitter
     *
     * @param prompt    用户提问提示词
     * @param retriever 知识库检索器
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithCitations(String prompt, ContentRetriever retriever) {
        return streamWithCitations(null, prompt, retriever);
    }

    /**
     * 指定模型结合 ContentRetriever 知识检索器发起可信 RAG 流式问答，自动创建并返回 SseEmitter
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提问提示词
     * @param retriever 知识库检索器
     * @return SseEmitter 实例
     */
    public SseEmitter streamWithCitations(String modelName, String prompt, ContentRetriever retriever) {
        SseEmitter emitter = new SseEmitter(DEFAULT_SSE_TIMEOUT.toMillis());
        streamWithCitations(modelName, prompt, retriever, emitter);
        return emitter;
    }

    /**
     * 结合 ContentRetriever 知识检索器发起可信 RAG 流式问答
     *
     * @param prompt    用户提问提示词
     * @param retriever 知识库检索器
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamWithCitations(String prompt, ContentRetriever retriever, SseEmitter emitter) {
        streamWithCitations(null, prompt, retriever, emitter);
    }

    /**
     * 指定模型结合 ContentRetriever 知识检索器发起可信 RAG 流式问答
     *
     * @param modelName 模型名称或别名
     * @param prompt    用户提问提示词
     * @param retriever 知识库检索器
     * @param emitter   Spring MVC SseEmitter 实例
     */
    public void streamWithCitations(String modelName, String prompt, ContentRetriever retriever, SseEmitter emitter) {
        if (!StringUtils.hasText(prompt)) {
            streamWithCitations(modelName, prompt, Collections.emptyList(), emitter, IdUtil.fastSimpleUUID());
            return;
        }
        List<TextSegment> candidates = Collections.emptyList();
        if (retriever != null) {
            List<Content> contents = retriever.retrieve(Query.from(prompt.trim()));
            candidates = CitationExtractor.fromContents(contents);
        }
        streamWithCitations(modelName, prompt, candidates, emitter, IdUtil.fastSimpleUUID());
    }

    /**
     * 单提示词底层流式响应消费接口 (使用当前上下文或默认模型)
     *
     * @param prompt  提示词
     * @param handler 自定义流式响应处理器
     */
    public void stream(String prompt, StreamingResponseHandler<AiMessage> handler) {
        streamWithModel(null, prompt, handler);
    }

    /**
     * 指定模型进行单提示词底层流式响应消费接口
     *
     * @param modelName 模型名称或别名
     * @param prompt    提示词
     * @param handler   自定义流式响应处理器
     */
    public void streamWithModel(String modelName, String prompt, StreamingResponseHandler<AiMessage> handler) {
        if (!StringUtils.hasText(prompt)) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "用户提示词 prompt 不能为空");
        }
        stream(modelName, Collections.singletonList(UserMessage.from(prompt.trim())), handler);
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

        // 0. 前置内容安全护栏检查 (PRE_PROMPT 防注入/越狱/超长)
        if (guardrail != null) {
            GuardrailResult preResult = guardrail.inspectMessages(messages);
            if (preResult.getAction() == GuardrailAction.BLOCK) {
                throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason());
            }
            if (preResult.getAction() == GuardrailAction.REPLACE) {
                handler.onNext(preResult.getSafeContent());
                handler.onComplete(Response.from(AiMessage.from(preResult.getSafeContent())));
                return;
            }
        }

        String effectiveModelName = resolveEffectiveModelName(modelName);
        String tenantId = TenantContextHolder.getTenantId();

        // 1. 前置配额与微观突发限流风控防卫 (Fail-Fast)
        int promptTokens = estimatePromptTokens(messages);
        if (rateLimiter != null) {
            rateLimiter.acquire(tenantId, effectiveModelName, promptTokens);
        }
        if (quotaChecker != null) {
            quotaChecker.checkQuota(tenantId, effectiveModelName, promptTokens);
        }

        StreamingChatLanguageModel model = resolveStreamingChatModel(effectiveModelName);
        StreamingResponseHandler<AiMessage> wrappedHandler = (quotaChecker != null)
                ? new QuotaTrackingStreamingResponseHandler(handler, quotaChecker, tenantId, effectiveModelName, messages)
                : handler;
        StreamingResponseHandler<AiMessage> guardrailHandler = (guardrail != null)
                ? new GuardrailStreamingResponseHandler(wrappedHandler, guardrail)
                : wrappedHandler;

        // 若当前未显式设置 streamId，但 handler 为带有 streamId 的 SseTokenStreamBridge，则自动填充与排空上下文
        boolean locallySetStreamId = false;
        if (StreamContextHolder.getStreamId() == null && handler instanceof SseTokenStreamBridge bridge && bridge.getStreamId() != null) {
            StreamContextHolder.setStreamId(bridge.getStreamId());
            locallySetStreamId = true;
        }

        try {
            model.generate(messages, guardrailHandler);
        } finally {
            if (locallySetStreamId) {
                StreamContextHolder.clear();
            }
        }
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

        // 0. 前置内容安全护栏检查 (PRE_PROMPT 防注入/越狱/超长)
        if (guardrail != null) {
            GuardrailResult preResult = guardrail.inspectMessages(messages);
            if (preResult.getAction() == GuardrailAction.BLOCK) {
                throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason());
            }
            if (preResult.getAction() == GuardrailAction.REPLACE) {
                return Response.from(AiMessage.from(preResult.getSafeContent()));
            }
        }

        String effectiveModelName = resolveEffectiveModelName(modelName);
        String tenantId = TenantContextHolder.getTenantId();

        // 1. 前置配额与微观突发限流风控防卫 (Fail-Fast)
        int promptTokens = estimatePromptTokens(messages);
        if (rateLimiter != null) {
            rateLimiter.acquire(tenantId, effectiveModelName, promptTokens);
        }
        if (quotaChecker != null) {
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

            // 3. 后置内容安全护栏检查 (POST_RESPONSE 防涉黄涉暴/违禁敏感词)
            if (guardrail != null) {
                String responseText = extractText(response);
                GuardrailResult postResult = guardrail.inspectResponse(responseText);
                if (postResult.getAction() == GuardrailAction.BLOCK) {
                    throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, postResult.getReason());
                }
                if (postResult.getAction() == GuardrailAction.REPLACE) {
                    return Response.from(AiMessage.from(postResult.getSafeContent()), response.tokenUsage(), response.finishReason());
                }
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
        String threadModel = AiContextHolder.getModelName();
        if (StringUtils.hasText(threadModel)) {
            return threadModel;
        }
        String target = modelName;
        if (tenantModelResolver != null && !TenantContextHolder.isIgnoreTenant()) {
            String tenantId = TenantContextHolder.getTenantId();
            if (StringUtils.hasText(tenantId)) {
                target = tenantModelResolver.resolveModel(tenantId, target);
            }
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
        return FallbackTokenEstimator.estimatePromptTokens(messages);
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
        String promptText = FallbackTokenEstimator.extractPromptText(messages);
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

    /**
     * 流式调用内容安全护栏响应处理器包装器
     */
    private static class GuardrailStreamingResponseHandler implements StreamingResponseHandler<AiMessage> {

        private final StreamingResponseHandler<AiMessage> delegate;
        private final AiGuardrail guardrail;
        private final StringBuilder fullContent = new StringBuilder();

        GuardrailStreamingResponseHandler(StreamingResponseHandler<AiMessage> delegate, AiGuardrail guardrail) {
            this.delegate = delegate;
            this.guardrail = guardrail;
        }

        @Override
        public void onNext(String token) {
            if (token != null) {
                fullContent.append(token);
            }
            delegate.onNext(token);
        }

        @Override
        public void onComplete(Response<AiMessage> response) {
            String fullText = fullContent.toString();
            GuardrailResult postResult = guardrail.inspectResponse(fullText);
            if (postResult.getAction() == GuardrailAction.BLOCK) {
                delegate.onError(new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, postResult.getReason()));
                return;
            }
            if (postResult.getAction() == GuardrailAction.REPLACE) {
                String safeText = postResult.getSafeContent();
                Response<AiMessage> sanitizedResponse = Response.from(
                        AiMessage.from(safeText != null ? safeText : ""),
                        response != null ? response.tokenUsage() : null,
                        response != null ? response.finishReason() : null
                );
                delegate.onComplete(sanitizedResponse);
                return;
            }
            delegate.onComplete(response);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }
    }
}
