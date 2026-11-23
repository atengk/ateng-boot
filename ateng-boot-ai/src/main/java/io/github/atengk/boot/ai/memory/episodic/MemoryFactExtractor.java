package io.github.atengk.boot.ai.memory.episodic;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.EpisodicMemoryProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 跨会话原子化长效情景记忆事实抽取器 (MemoryFactExtractor)
 * <p>
 * 依托大模型提示词工程从历史多轮会话记录中提取结构化原子事实 ({@link MemoryFact})；
 * 支持受管异步线程池非阻塞调度 ({@link #extractAsync})，并在异步调度中通过 {@link AsyncContext}
 * 严密透传 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 上下文快照。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class MemoryFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryFactExtractor.class);

    private static final Executor DEFAULT_EXECUTOR = new ThreadPoolExecutor(
            2,
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            new NamedThreadFactory("ai-fact-extract-async", true),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 默认长效事实抽取提示词模板
     */
    public static final String DEFAULT_EXTRACTION_PROMPT_TEMPLATE = """
            你是一个长效情景记忆事实抽取专家。请从以下对话记录中，抽取出关于用户或实体的持久化原子事实（如用户身份、偏好、习惯、约束、关键背景等）。
            必须以纯 JSON 数组格式输出，不要输出任何多余的解释、Markdown 标记或代码块之外的字符。
            每个事实包含以下字段：
            - "subject": 事实主体（例如 "user", "order_123" 等）
            - "predicate": 事实谓词/属性键（例如 "preferred_language", "dietary_preference", "programming_language", "city" 等）
            - "object": 事实客体/值（例如 "zh-CN", "vegetarian", "Java", "Beijing" 等）
            - "statement": 事实的自然语言完整陈述句（例如 "用户的首选编程语言是 Java"）
            - "confidence": 置信度浮点数（0.0 ~ 1.0 之间）

            若对话中未包含任何需要长期记住的新事实，请返回空数组 []。

            待分析对话记录：
            %s
            """;

    private final ChatLanguageModel defaultChatModel;
    private final AiModelRegistry modelRegistry;
    private final EpisodicMemoryProperties episodicProperties;
    private final AtengAiProperties rootProperties;
    private final Executor executor;

    public MemoryFactExtractor(ChatLanguageModel defaultChatModel) {
        this(defaultChatModel, null, null, null, null);
    }

    public MemoryFactExtractor(ChatLanguageModel defaultChatModel,
                               AiModelRegistry modelRegistry,
                               EpisodicMemoryProperties episodicProperties,
                               AtengAiProperties rootProperties,
                               Executor executor) {
        this.defaultChatModel = defaultChatModel;
        this.modelRegistry = modelRegistry;
        this.episodicProperties = episodicProperties != null ? episodicProperties : new EpisodicMemoryProperties();
        this.rootProperties = rootProperties;
        this.executor = executor != null ? executor : DEFAULT_EXECUTOR;
    }

    /**
     * 判断当前抽取器是否已就绪（底层大模型是否可用）
     *
     * @return true 若就绪
     */
    public boolean isAvailable() {
        return resolveChatModel() != null;
    }

    /**
     * 解析执行事实抽取的 ChatLanguageModel 实例
     *
     * @return ChatLanguageModel 实例，若未配置返回 null
     */
    protected ChatLanguageModel resolveChatModel() {
        if (episodicProperties != null && StringUtils.hasText(episodicProperties.getExtractModel()) && modelRegistry != null) {
            ChatLanguageModel namedModel = modelRegistry.getChatModel(episodicProperties.getExtractModel().trim());
            if (namedModel != null) {
                return namedModel;
            }
        }
        if (this.defaultChatModel != null) {
            return this.defaultChatModel;
        }
        if (this.modelRegistry != null) {
            return this.modelRegistry.getChatModel();
        }
        return null;
    }

    /**
     * 从纯文本对话历史记录中同步抽取原子事实列表
     *
     * @param conversationText 对话文本
     * @return 抽取的 MemoryFact 事实列表 (保底返回非空集合)
     */
    public List<MemoryFact> extract(String conversationText) {
        return extract(conversationText, null, null);
    }

    /**
     * 从纯文本对话历史记录中同步抽取原子事实列表 (显式指定用户与租户)
     *
     * @param conversationText 对话文本
     * @param userId           目标用户标识 (传空则尝试从 UserContextHolder 读取)
     * @param tenantId         目标租户标识 (传空则尝试从 TenantContextHolder 读取)
     * @return 抽取的 MemoryFact 事实列表 (保底返回非空集合)
     */
    public List<MemoryFact> extract(String conversationText, String userId, String tenantId) {
        if (!StringUtils.hasText(conversationText)) {
            return Collections.emptyList();
        }

        ChatLanguageModel model = resolveChatModel();
        if (model == null) {
            log.warn("[EPISODIC-FACT-EXTRACTOR] 未检测到可用的 ChatLanguageModel，跳过事实抽取");
            return Collections.emptyList();
        }

        String effectiveUserId = StringUtils.hasText(userId) ? userId.trim() : UserContextHolder.getUserId();
        String effectiveTenantId = StringUtils.hasText(tenantId) ? tenantId.trim() : TenantContextHolder.getTenantId();

        String prompt = String.format(DEFAULT_EXTRACTION_PROMPT_TEMPLATE, conversationText.trim());
        try {
            Response<AiMessage> response = model.generate(UserMessage.from(prompt));
            if (response == null || response.content() == null || !StringUtils.hasText(response.content().text())) {
                return Collections.emptyList();
            }

            String rawJson = response.content().text().trim();
            return parseFactsFromJson(rawJson, effectiveUserId, effectiveTenantId);
        } catch (Throwable t) {
            log.warn("[EPISODIC-FACT-EXTRACTOR] 事实抽取大模型调用或解析异常: {}", t.getMessage(), t);
            return Collections.emptyList();
        }
    }

    /**
     * 从 LangChain4j ChatMessage 消息列表中同步抽取原子事实列表
     *
     * @param messages 消息列表
     * @return 抽取的 MemoryFact 事实列表 (保底返回非空集合)
     */
    public List<MemoryFact> extract(List<ChatMessage> messages) {
        return extract(messages, null, null);
    }

    /**
     * 从 LangChain4j ChatMessage 消息列表中同步抽取原子事实列表 (显式指定用户与租户)
     *
     * @param messages 消息列表
     * @param userId   目标用户标识
     * @param tenantId 目标租户标识
     * @return 抽取的 MemoryFact 事实列表 (保底返回非空集合)
     */
    public List<MemoryFact> extract(List<ChatMessage> messages, String userId, String tenantId) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        String conversationText = formatMessages(messages);
        return extract(conversationText, userId, tenantId);
    }

    /**
     * 异步非阻塞抽取原子事实 (四维上下文全链路透传)
     *
     * @param conversationText 对话文本
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<MemoryFact>> extractAsync(String conversationText) {
        return extractAsync(conversationText, null, null);
    }

    /**
     * 异步非阻塞抽取原子事实 (显式指定用户与租户，四维上下文全链路透传)
     *
     * @param conversationText 对话文本
     * @param userId           目标用户标识
     * @param tenantId         目标租户标识
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<MemoryFact>> extractAsync(String conversationText, String userId, String tenantId) {
        return CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> extract(conversationText, userId, tenantId)),
                this.executor
        );
    }

    /**
     * 异步非阻塞从 ChatMessage 列表中抽取原子事实
     *
     * @param messages 消息列表
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<MemoryFact>> extractAsync(List<ChatMessage> messages) {
        return extractAsync(messages, null, null);
    }

    /**
     * 异步非阻塞从 ChatMessage 列表中抽取原子事实 (显式指定用户与租户)
     *
     * @param messages 消息列表
     * @param userId   目标用户标识
     * @param tenantId 目标租户标识
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<MemoryFact>> extractAsync(List<ChatMessage> messages, String userId, String tenantId) {
        return CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> extract(messages, userId, tenantId)),
                this.executor
        );
    }

    /**
     * 解析模型返回的 JSON 数组并映射为标准 MemoryFact 实体列表
     *
     * @param rawText  模型原始输出文本
     * @param userId   用户标识
     * @param tenantId 租户标识
     * @return MemoryFact 列表
     */
    public List<MemoryFact> parseFactsFromJson(String rawText, String userId, String tenantId) {
        if (!StringUtils.hasText(rawText)) {
            return Collections.emptyList();
        }

        String cleanedJson = sanitizeJsonArray(rawText);
        if (!StringUtils.hasText(cleanedJson) || "[]".equals(cleanedJson)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rawList;
        try {
            rawList = JsonUtil.getObjectMapper().readValue(cleanedJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[EPISODIC-FACT-EXTRACTOR] 解析抽取事实 JSON 数组失败: text={}, err={}", cleanedJson, e.getMessage());
            return Collections.emptyList();
        }

        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<MemoryFact> result = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map<String, Object> map : rawList) {
            String subject = getString(map, "subject");
            String predicate = getString(map, "predicate");
            String object = getString(map, "object");
            String statement = getString(map, "statement");
            Double confidence = getDouble(map, "confidence");

            if (!StringUtils.hasText(subject) || !StringUtils.hasText(predicate)) {
                continue;
            }

            MemoryFact fact = MemoryFact.builder()
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .userId(userId)
                    .tenantId(tenantId)
                    .subject(subject.trim())
                    .predicate(predicate.trim())
                    .object(object != null ? object.trim() : "")
                    .statement(StringUtils.hasText(statement) ? statement.trim() : subject + " " + predicate + ": " + object)
                    .confidence(confidence != null ? confidence : 1.0)
                    .timestamp(now)
                    .build();

            result.add(fact);
        }

        return result;
    }

    private static String sanitizeJsonArray(String raw) {
        if (raw == null) {
            return "[]";
        }
        String text = raw.trim();
        // 剔除可能的 markdown 代码块围栏 ```json ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }

        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }
        return "[]";
    }

    private static String formatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                sb.append("User: ").append(um.singleText()).append("\n");
            } else if (msg instanceof AiMessage am) {
                sb.append("Assistant: ").append(am.text()).append("\n");
            } else if (msg instanceof SystemMessage sm) {
                sb.append("System: ").append(sm.text()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val).trim() : null;
    }

    private static Double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s && StringUtils.hasText(s)) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
