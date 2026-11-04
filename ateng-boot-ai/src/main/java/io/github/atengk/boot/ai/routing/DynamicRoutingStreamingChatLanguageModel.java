package io.github.atengk.boot.ai.routing;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 动态路由与多模型切源流式聊天模型代理
 * 贯通运行时流式模型路由优先级：AiContextHolder 线程级切源 > AiTenantModelResolver (多租户动态切源) > @AiAgent(model) 接口静态配置 > 默认主配置模型
 * 同时内置配额风控守卫、提示词复合入参替换与 Token 消耗自动统计流水线。
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class DynamicRoutingStreamingChatLanguageModel implements StreamingChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicRoutingStreamingChatLanguageModel.class);

    private final AiModelRegistry registry;
    private final String configuredModelName;
    private final AiTenantModelResolver tenantModelResolver;
    private final AiQuotaChecker quotaChecker;

    public DynamicRoutingStreamingChatLanguageModel(AiModelRegistry registry, String configuredModelName) {
        this(registry, configuredModelName, null, null);
    }

    public DynamicRoutingStreamingChatLanguageModel(AiModelRegistry registry, String configuredModelName, AiTenantModelResolver tenantModelResolver) {
        this(registry, configuredModelName, tenantModelResolver, null);
    }

    public DynamicRoutingStreamingChatLanguageModel(AiModelRegistry registry, String configuredModelName,
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
     * 动态推导当前应生效的底层流式大模型实例
     *
     * @return 生效的 StreamingChatLanguageModel
     */
    public StreamingChatLanguageModel resolveModel() {
        return resolveModel(resolveModelName());
    }

    /**
     * 根据指定模型名称获取生效的 StreamingChatLanguageModel
     *
     * @param modelName 目标模型名称
     * @return 生效的 StreamingChatLanguageModel
     */
    public StreamingChatLanguageModel resolveModel(String modelName) {
        if (StringUtils.hasText(modelName)) {
            return registry.getStreamingChatModel(modelName);
        }
        return registry.getStreamingChatModel(AiModelRegistry.DEFAULT_MODEL_NAME);
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        executeWithRouting(messages, handler, (model, preprocessed, wrappedHandler) -> model.generate(preprocessed, wrappedHandler));
    }

    @Override
    public void generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications, StreamingResponseHandler<AiMessage> handler) {
        executeWithRouting(messages, handler, (model, preprocessed, wrappedHandler) -> model.generate(preprocessed, toolSpecifications, wrappedHandler));
    }

    @Override
    public void generate(List<ChatMessage> messages, ToolSpecification toolSpecification, StreamingResponseHandler<AiMessage> handler) {
        executeWithRouting(messages, handler, (model, preprocessed, wrappedHandler) -> model.generate(preprocessed, toolSpecification, wrappedHandler));
    }

    @FunctionalInterface
    private interface StreamingExecutor {
        void execute(StreamingChatLanguageModel model, List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler);
    }

    private void executeWithRouting(List<ChatMessage> messages,
                                    StreamingResponseHandler<AiMessage> handler,
                                    StreamingExecutor executor) {
        Objects.requireNonNull(handler, "StreamingResponseHandler 不能为空");
        List<ChatMessage> preprocessed = preprocessMessages(messages);
        String modelName = resolveModelName();
        StreamingChatLanguageModel model = resolveModel(modelName);

        String tenantId = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
        if (quotaChecker != null && StringUtils.hasText(tenantId)) {
            int promptTokens = FallbackTokenEstimator.estimatePromptTokens(preprocessed);
            quotaChecker.checkQuota(tenantId.trim(), modelName, promptTokens);
        }

        StreamingResponseHandler<AiMessage> wrappedHandler = (quotaChecker != null && StringUtils.hasText(tenantId))
                ? new RoutingStreamingQuotaTrackingHandler(handler, quotaChecker, tenantId.trim(), modelName, preprocessed)
                : handler;

        executor.execute(model, preprocessed, wrappedHandler);
    }

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

    private ChatMessage processSingleMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            if (um.contents() != null && !um.contents().isEmpty()) {
                boolean anyReplaced = false;
                List<Content> newContents = new ArrayList<>(um.contents().size());
                for (Content c : um.contents()) {
                    if (c instanceof TextContent tc) {
                        String original = tc.text();
                        String replaced = AgentCallContext.applyReplacements(original);
                        if (replaced != null && !replaced.equals(original)) {
                            newContents.add(TextContent.from(replaced));
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

    private static class RoutingStreamingQuotaTrackingHandler implements StreamingResponseHandler<AiMessage> {

        private final StreamingResponseHandler<AiMessage> delegate;
        private final AiQuotaChecker quotaChecker;
        private final String tenantId;
        private final String modelName;
        private final List<ChatMessage> messages;

        RoutingStreamingQuotaTrackingHandler(StreamingResponseHandler<AiMessage> delegate,
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
                int totalTokens;
                if (response != null && response.tokenUsage() != null && response.tokenUsage().totalTokenCount() != null) {
                    totalTokens = response.tokenUsage().totalTokenCount();
                } else {
                    String promptText = FallbackTokenEstimator.extractPromptText(messages);
                    String replyText = (response != null && response.content() != null && response.content().text() != null)
                            ? response.content().text() : "";
                    totalTokens = FallbackTokenEstimator.estimate(promptText, replyText).totalTokenCount();
                }
                quotaChecker.recordUsage(tenantId, modelName, totalTokens);
            } catch (Throwable t) {
                log.warn("流式智能体调用完成后记录配额消耗失败: {}", t.getMessage());
            }
            delegate.onComplete(response);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }
    }
}
