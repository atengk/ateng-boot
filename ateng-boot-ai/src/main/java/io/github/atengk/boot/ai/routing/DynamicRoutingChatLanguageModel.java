package io.github.atengk.boot.ai.routing;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.atengk.boot.ai.cache.AiCacheLookupResult;
import io.github.atengk.boot.ai.cache.AiSemanticCache;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.guardrail.AiGuardrail;
import io.github.atengk.boot.ai.guardrail.GuardrailAction;
import io.github.atengk.boot.ai.guardrail.GuardrailResult;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.ratelimit.AiRateLimiter;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.sanitizer.JsonPayloadSanitizer;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 动态路由与多模型切源聊天模型代理
 * 贯通运行时模型路由优先级：AiContextHolder 线程级切源 > AiTenantModelResolver (多租户动态切源) > @AiAgent(model) 接口静态配置 > 默认主配置模型
 * 同时内置配额风控守卫、提示词复合入参替换与大模型结构化输出 Markdown 脱壳去噪流水线。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class DynamicRoutingChatLanguageModel implements ChatLanguageModel {

    private final AiModelRegistry registry;
    private final String configuredModelName;
    private final AiTenantModelResolver tenantModelResolver;
    private final AiQuotaChecker quotaChecker;
    private final AiRateLimiter rateLimiter;
    private final AiSemanticCache semanticCache;
    private final AtengAiProperties aiProperties;
    private final AiGuardrail preGuardrail;
    private final AiGuardrail postGuardrail;

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName) {
        this(registry, configuredModelName, null, null, null, null, null, null, null);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName, AiTenantModelResolver tenantModelResolver) {
        this(registry, configuredModelName, tenantModelResolver, null, null, null, null, null, null);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName,
                                           AiTenantModelResolver tenantModelResolver, AiQuotaChecker quotaChecker) {
        this(registry, configuredModelName, tenantModelResolver, quotaChecker, null, null, null, null, null);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName,
                                           AiTenantModelResolver tenantModelResolver, AiQuotaChecker quotaChecker,
                                           AiRateLimiter rateLimiter) {
        this(registry, configuredModelName, tenantModelResolver, quotaChecker, rateLimiter, null, null, null, null);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName,
                                           AiTenantModelResolver tenantModelResolver, AiQuotaChecker quotaChecker,
                                           AiRateLimiter rateLimiter, AiSemanticCache semanticCache,
                                           AtengAiProperties aiProperties, AiGuardrail guardrail) {
        this(registry, configuredModelName, tenantModelResolver, quotaChecker, rateLimiter,
                semanticCache, aiProperties, guardrail, guardrail);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName,
                                           AiTenantModelResolver tenantModelResolver, AiQuotaChecker quotaChecker,
                                           AiRateLimiter rateLimiter, AiSemanticCache semanticCache,
                                           AtengAiProperties aiProperties, AiGuardrail preGuardrail,
                                           AiGuardrail postGuardrail) {
        this.registry = Objects.requireNonNull(registry, "AiModelRegistry 不能为空");
        this.configuredModelName = StringUtils.hasText(configuredModelName) ? configuredModelName.trim() : "";
        this.tenantModelResolver = tenantModelResolver;
        this.quotaChecker = quotaChecker;
        this.rateLimiter = rateLimiter;
        this.semanticCache = semanticCache;
        this.aiProperties = aiProperties;
        this.preGuardrail = preGuardrail;
        this.postGuardrail = postGuardrail;
    }

    /**
     * 动态推导当前应生效的目标大模型名称
     * 优先级：AiContextHolder (线程级) > AiTenantModelResolver (多租户动态切源) > configuredModelName (@AiAgent 静态配置) > 默认模型
     *
     * @return 生效的目标模型名称
     */
    public String resolveModelName() {
        return DynamicRoutingSupport.resolveModelName(configuredModelName, tenantModelResolver);
    }

    /**
     * 动态推导当前应生效的底层大模型实例
     *
     * @return 生效的 ChatLanguageModel
     */
    public ChatLanguageModel resolveModel() {
        return resolveModel(resolveModelName());
    }

    /**
     * 根据指定模型名称获取生效的 ChatLanguageModel
     *
     * @param modelName 目标模型名称
     * @return 生效的 ChatLanguageModel
     */
    public ChatLanguageModel resolveModel(String modelName) {
        if (StringUtils.hasText(modelName)) {
            return registry.getChatModel(modelName);
        }
        return registry.getChatModel(AiModelRegistry.DEFAULT_MODEL_NAME);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return executeWithRouting(messages, false, (model, preprocessed) -> model.generate(preprocessed));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        boolean hasTools = toolSpecifications != null && !toolSpecifications.isEmpty();
        return executeWithRouting(messages, hasTools, (model, preprocessed) -> model.generate(preprocessed, toolSpecifications));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return executeWithRouting(messages, toolSpecification != null, (model, preprocessed) -> model.generate(preprocessed, toolSpecification));
    }

    /**
     * 统一路由执行骨架：消息前置预处理 -> 前置护栏 -> 双级语义缓存 -> 限流/配额守卫 -> 底层调用 -> 用量记录 -> 响应去噪/后置护栏 -> 回写缓存
     */
    private Response<AiMessage> executeWithRouting(List<ChatMessage> messages,
                                                   boolean hasTools,
                                                   java.util.function.BiFunction<ChatLanguageModel, List<ChatMessage>, Response<AiMessage>> executor) {
        List<ChatMessage> preprocessed = preprocessMessages(messages);

        if (preGuardrail != null && preprocessed != null && !preprocessed.isEmpty()) {
            // 仅审查非 SystemMessage 的交互入参，避免将智能体 @SystemMessage 中的合规审查规则误判为违规输入
            List<ChatMessage> userMessages = preprocessed.stream()
                    .filter(msg -> !(msg instanceof SystemMessage))
                    .toList();
            if (!userMessages.isEmpty()) {
                GuardrailResult preResult = preGuardrail.inspectMessages(userMessages);
                if (preResult.getAction() == GuardrailAction.BLOCK) {
                    throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason());
                }
                if (preResult.getAction() == GuardrailAction.REPLACE) {
                    return Response.from(AiMessage.from(preResult.getSafeContent()));
                }
            }
        }

        String modelName = resolveModelName();
        AiModelConfig modelConfig = aiProperties != null ? aiProperties.resolveConfig(modelName) : null;

        if (semanticCache != null && !hasTools) {
            Optional<AiCacheLookupResult> cached = semanticCache.get(modelName, preprocessed, modelConfig, false, false);
            if (cached.isPresent()) {
                return Response.from(AiMessage.from(cached.get().getResponseText()));
            }
        }

        ChatLanguageModel model = resolveModel(modelName);

        String tenantId = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
        int promptTokens = (rateLimiter != null || (quotaChecker != null && StringUtils.hasText(tenantId)))
                ? FallbackTokenEstimator.estimatePromptTokens(preprocessed)
                : 0;
        if (rateLimiter != null) {
            rateLimiter.acquire(tenantId, modelName, promptTokens);
        }
        if (quotaChecker != null && StringUtils.hasText(tenantId)) {
            quotaChecker.checkQuota(tenantId.trim(), modelName, promptTokens);
        }

        Response<AiMessage> response = executor.apply(model, preprocessed);

        if (quotaChecker != null && StringUtils.hasText(tenantId) && response != null) {
            TokenUsage usage = response.tokenUsage();
            int totalTokens = (usage != null && usage.totalTokenCount() != null)
                    ? usage.totalTokenCount()
                    : FallbackTokenEstimator.estimateTokens(response.content() != null ? response.content().text() : "");
            quotaChecker.recordUsage(tenantId.trim(), modelName, totalTokens);
        }

        Response<AiMessage> sanitizedResponse = postprocessResponse(response);

        boolean postGuardrailPassed = true;
        if (postGuardrail != null && sanitizedResponse != null && sanitizedResponse.content() != null
                && !sanitizedResponse.content().hasToolExecutionRequests()
                && StringUtils.hasText(sanitizedResponse.content().text())) {
            GuardrailResult postResult = postGuardrail.inspectResponse(sanitizedResponse.content().text());
            if (postResult.getAction() == GuardrailAction.BLOCK) {
                throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, postResult.getReason());
            }
            if (postResult.getAction() == GuardrailAction.REPLACE) {
                postGuardrailPassed = false;
                sanitizedResponse = Response.from(
                        AiMessage.from(postResult.getSafeContent()),
                        sanitizedResponse.tokenUsage(),
                        sanitizedResponse.finishReason());
            }
        }

        if (semanticCache != null && !hasTools && postGuardrailPassed
                && sanitizedResponse != null && sanitizedResponse.content() != null
                && !sanitizedResponse.content().hasToolExecutionRequests()
                && StringUtils.hasText(sanitizedResponse.content().text())) {
            semanticCache.put(modelName, preprocessed, sanitizedResponse.content().text(), modelConfig, false, false);
        }

        return sanitizedResponse;
    }

    private List<ChatMessage> preprocessMessages(List<ChatMessage> messages) {
        return DynamicRoutingSupport.preprocessMessages(messages);
    }

    /**
     * 后置处理响应：仅当包含 Markdown ```json 结构化代码块时剥除外壳，严格保护普通 Markdown 文本与非 JSON 代码块
     */
    private Response<AiMessage> postprocessResponse(Response<AiMessage> response) {
        if (response == null || response.content() == null) {
            return response;
        }
        AiMessage content = response.content();
        String text = content.text();
        if (StringUtils.hasText(text) && text.contains("```") && JsonPayloadSanitizer.hasMarkdownJsonBlock(text)) {
            String sanitized = JsonPayloadSanitizer.sanitize(text);
            if (StringUtils.hasText(sanitized) && !sanitized.equals(text)) {
                AiMessage newContent = content.hasToolExecutionRequests()
                        ? AiMessage.from(sanitized, content.toolExecutionRequests())
                        : AiMessage.from(sanitized);
                return Response.from(newContent, response.tokenUsage(), response.finishReason());
            }
        }
        return response;
    }
}
