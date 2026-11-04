package io.github.atengk.boot.ai.routing;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.sanitizer.JsonPayloadSanitizer;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName) {
        this(registry, configuredModelName, null, null);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName, AiTenantModelResolver tenantModelResolver) {
        this(registry, configuredModelName, tenantModelResolver, null);
    }

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName,
                                           AiTenantModelResolver tenantModelResolver, AiQuotaChecker quotaChecker) {
        this.registry = Objects.requireNonNull(registry, "AiModelRegistry 不能为空");
        this.configuredModelName = StringUtils.hasText(configuredModelName) ? configuredModelName.trim() : "";
        this.tenantModelResolver = tenantModelResolver;
        this.quotaChecker = quotaChecker;
    }

    /**
     * 动态推导当前应生效的目标大模型名称
     * 优先级：AiContextHolder (线程级) > AiTenantModelResolver (多租户动态切源) > configuredModelName (@AiAgent 静态配置) > 默认模型
     *
     * @return 生效的目标模型名称
     */
    public String resolveModelName() {
        String threadModel = AiContextHolder.getModelName();
        if (StringUtils.hasText(threadModel)) {
            return threadModel.trim();
        }
        String target = configuredModelName;
        if (tenantModelResolver != null && !TenantContextHolder.isIgnoreTenant()) {
            String tenantId = TenantContextHolder.getTenantId();
            if (StringUtils.hasText(tenantId)) {
                target = tenantModelResolver.resolveModel(tenantId.trim(), target);
            }
        }
        if (StringUtils.hasText(target)) {
            return target;
        }
        return AiModelRegistry.DEFAULT_MODEL_NAME;
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
        return executeWithRouting(messages, (model, preprocessed) -> model.generate(preprocessed));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return executeWithRouting(messages, (model, preprocessed) -> model.generate(preprocessed, toolSpecifications));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return executeWithRouting(messages, (model, preprocessed) -> model.generate(preprocessed, toolSpecification));
    }

    /**
     * 统一路由执行骨架：消息前置预处理 -> 解析目标模型 -> 配额守卫拦截 -> 底层调用 -> 配额用量记录 -> 响应安全后置处理
     */
    private Response<AiMessage> executeWithRouting(List<ChatMessage> messages,
                                                   java.util.function.BiFunction<ChatLanguageModel, List<ChatMessage>, Response<AiMessage>> executor) {
        List<ChatMessage> preprocessed = preprocessMessages(messages);
        String modelName = resolveModelName();
        ChatLanguageModel model = resolveModel(modelName);

        String tenantId = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
        if (quotaChecker != null && StringUtils.hasText(tenantId)) {
            int promptTokens = FallbackTokenEstimator.estimatePromptTokens(preprocessed);
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

        return postprocessResponse(response);
    }

    /**
     * 前置处理消息：针对包含复合对象入参的 UserMessage / SystemMessage，自动应用 JSON 格式化替换
     */
    private List<ChatMessage> preprocessMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>(messages.size());
        boolean modified = false;

        for (ChatMessage msg : messages) {
            ChatMessage processed = processSingleMessage(msg);
            if (processed != msg) {
                result.add(processed);
                modified = true;
            } else {
                result.add(msg);
            }
        }

        return modified ? result : messages;
    }

    /**
     * 对单条消息执行入参标记替换，收敛重复分支逻辑
     */
    private ChatMessage processSingleMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            if (um.contents() != null && !um.contents().isEmpty()) {
                boolean anyReplaced = false;
                List<dev.langchain4j.data.message.Content> newContents = new ArrayList<>(um.contents().size());
                for (dev.langchain4j.data.message.Content c : um.contents()) {
                    if (c instanceof dev.langchain4j.data.message.TextContent tc) {
                        String original = tc.text();
                        String replaced = AgentCallContext.applyReplacements(original);
                        if (replaced != null && !replaced.equals(original)) {
                            newContents.add(dev.langchain4j.data.message.TextContent.from(replaced));
                            anyReplaced = true;
                            continue;
                        }
                    }
                    newContents.add(c);
                }
                if (anyReplaced) {
                    return UserMessage.from(newContents);
                }
            } else if (um.hasSingleText()) {
                String original = um.singleText();
                String replaced = AgentCallContext.applyReplacements(original);
                if (replaced != null && !replaced.equals(original)) {
                    return UserMessage.from(replaced);
                }
            }
        } else if (msg instanceof SystemMessage sm) {
            String original = sm.text();
            String replaced = AgentCallContext.applyReplacements(original);
            if (replaced != null && !replaced.equals(original)) {
                return SystemMessage.from(replaced);
            }
        }
        return msg;
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
