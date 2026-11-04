package io.github.atengk.boot.ai.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequest;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponse;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.github.atengk.boot.ai.properties.AiAuditProperties;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI 大模型调用全维可观测审计监听器
 * 基于 LangChain4j 原生 ChatModelListener SPI 规范，在不引入重型依赖的前提下透明拦截大模型请求生命周期，
 * 记录调用耗时、时间戳、模型元数据，还原四维上下文，实施 PII 正则脱敏，并提供非阻塞异步扩展点回调。
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class AtengAiObservabilityListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(AtengAiObservabilityListener.class);

    public static final String ATTR_START_TIME = "ateng.ai.startTime";
    public static final String ATTR_START_INSTANT = "ateng.ai.startInstant";
    public static final String ATTR_TRACE_ID = "ateng.ai.traceId";
    public static final String ATTR_USER_ID = "ateng.ai.userId";
    public static final String ATTR_TENANT_ID = "ateng.ai.tenantId";
    public static final String ATTR_CONTEXT_SNAPSHOT = "ateng.ai.contextSnapshot";

    private final AtengAiProperties properties;
    private final ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider;
    private final ObjectProvider<TaskExecutor> taskExecutorProvider;

    public AtengAiObservabilityListener(AtengAiProperties properties,
                                        ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider,
                                        ObjectProvider<TaskExecutor> taskExecutorProvider) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.auditLogHandlerProvider = auditLogHandlerProvider;
        this.taskExecutorProvider = taskExecutorProvider;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        if (!isAuditEnabled() || requestContext == null) {
            return;
        }

        Map<Object, Object> attributes = requestContext.attributes();
        if (attributes != null) {
            safePut(attributes, ATTR_START_TIME, System.currentTimeMillis());
            safePut(attributes, ATTR_START_INSTANT, Instant.now());
            safePut(attributes, ATTR_TRACE_ID, MDC.get("traceId"));
            safePut(attributes, ATTR_USER_ID, UserContextHolder.getUserId());
            safePut(attributes, ATTR_TENANT_ID, TenantContextHolder.getTenantId());
            safePut(attributes, ATTR_CONTEXT_SNAPSHOT, AsyncContext.ContextSnapshot.capture());
        }

        if (log.isDebugEnabled()) {
            ChatModelRequest req = requestContext.request();
            String model = req != null ? req.model() : "unknown";
            String traceId = MDC.get("traceId");
            log.debug("[AI-AUDIT-START] [traceId={}] 大模型调用发起: model={}", traceId, model);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        if (!isAuditEnabled() || responseContext == null) {
            return;
        }

        Map<Object, Object> attributes = responseContext.attributes();
        executeWithRestoredContext(attributes, () -> handleResponse(responseContext));
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        if (!isAuditEnabled() || errorContext == null) {
            return;
        }

        Map<Object, Object> attributes = errorContext.attributes();
        executeWithRestoredContext(attributes, () -> handleError(errorContext));
    }

    private void handleResponse(ChatModelResponseContext responseContext) {
        Map<Object, Object> attributes = responseContext.attributes();
        long startTime = getStartTime(attributes);
        Instant startInstant = getStartInstant(attributes);
        Instant endInstant = Instant.now();
        long durationMs = Math.max(0, System.currentTimeMillis() - startTime);

        ChatModelRequest request = responseContext.request();
        ChatModelResponse response = responseContext.response();

        String modelName = resolveModelName(request, response);
        TokenUsage usage = response != null ? response.tokenUsage() : null;
        Integer promptTokens = usage != null ? usage.inputTokenCount() : null;
        Integer completionTokens = usage != null ? usage.outputTokenCount() : null;
        Integer totalTokens = usage != null ? usage.totalTokenCount() : null;

        String rawPrompt = extractPromptText(request);
        String rawResponse = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;

        AiAuditProperties auditProps = getAuditProperties();
        String promptText = auditProps.isMaskPii() ? AiPiiMasker.mask(rawPrompt) : rawPrompt;
        String responseText = auditProps.isMaskPii() ? AiPiiMasker.mask(rawResponse) : rawResponse;

        String traceId = (String) (attributes != null ? attributes.get(ATTR_TRACE_ID) : MDC.get("traceId"));
        String userId = (String) (attributes != null ? attributes.get(ATTR_USER_ID) : UserContextHolder.getUserId());
        String tenantId = (String) (attributes != null ? attributes.get(ATTR_TENANT_ID) : TenantContextHolder.getTenantId());

        if (auditProps.isLogPayload()) {
            log.info("[AI-AUDIT-SUCCESS] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}, prompt='{}', response='{}'",
                    traceId, tenantId, userId, modelName, durationMs, promptTokens, completionTokens, totalTokens, promptText, responseText);
        } else {
            log.info("[AI-AUDIT-SUCCESS] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}",
                    traceId, tenantId, userId, modelName, durationMs, promptTokens, completionTokens, totalTokens);
        }

        AiAuditRecord record = AiAuditRecord.builder()
                .traceId(traceId)
                .userId(userId)
                .tenantId(tenantId)
                .modelName(modelName)
                .durationMs(durationMs)
                .requestTime(startInstant)
                .responseTime(endInstant)
                .status("SUCCESS")
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .estimated(false)
                .promptText(promptText)
                .responseText(responseText)
                .metadata(Collections.emptyMap())
                .build();

        dispatchAuditLog(record);
    }

    private void handleError(ChatModelErrorContext errorContext) {
        Map<Object, Object> attributes = errorContext.attributes();
        long startTime = getStartTime(attributes);
        Instant startInstant = getStartInstant(attributes);
        Instant endInstant = Instant.now();
        long durationMs = Math.max(0, System.currentTimeMillis() - startTime);

        ChatModelRequest request = errorContext.request();
        ChatModelResponse partialResponse = errorContext.partialResponse();
        Throwable error = errorContext.error();

        String modelName = resolveModelName(request, partialResponse);
        String rawPrompt = extractPromptText(request);
        AiAuditProperties auditProps = getAuditProperties();
        String promptText = auditProps.isMaskPii() ? AiPiiMasker.mask(rawPrompt) : rawPrompt;

        String traceId = (String) (attributes != null ? attributes.get(ATTR_TRACE_ID) : MDC.get("traceId"));
        String userId = (String) (attributes != null ? attributes.get(ATTR_USER_ID) : UserContextHolder.getUserId());
        String tenantId = (String) (attributes != null ? attributes.get(ATTR_TENANT_ID) : TenantContextHolder.getTenantId());

        boolean clientAbort = isClientAbort(error);
        String status = clientAbort ? "CANCELLED" : "ERROR";
        String errorMsg = error != null ? error.getMessage() : "Unknown error";

        if (clientAbort) {
            log.warn("[AI-AUDIT-CANCELLED] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, reason=客户端主动中断连接: {}",
                    traceId, tenantId, userId, modelName, durationMs, errorMsg);
        } else {
            log.error("[AI-AUDIT-ERROR] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, error={}",
                    traceId, tenantId, userId, modelName, durationMs, errorMsg);
        }

        AiAuditRecord record = AiAuditRecord.builder()
                .traceId(traceId)
                .userId(userId)
                .tenantId(tenantId)
                .modelName(modelName)
                .durationMs(durationMs)
                .requestTime(startInstant)
                .responseTime(endInstant)
                .status(status)
                .promptText(promptText)
                .errorMessage(errorMsg)
                .metadata(Collections.emptyMap())
                .build();

        dispatchAuditLog(record);
    }

    private void executeWithRestoredContext(Map<Object, Object> attributes, Runnable task) {
        AsyncContext.ContextSnapshot snapshot = null;
        if (attributes != null && attributes.get(ATTR_CONTEXT_SNAPSHOT) instanceof AsyncContext.ContextSnapshot s) {
            snapshot = s;
        }
        AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
        try {
            if (snapshot != null) {
                snapshot.apply();
            }
            task.run();
        } finally {
            previous.restore();
        }
    }

    private void dispatchAuditLog(AiAuditRecord record) {
        if (auditLogHandlerProvider == null) {
            return;
        }
        AiAuditLogHandler handler = auditLogHandlerProvider.getIfAvailable();
        if (handler == null) {
            return;
        }

        TaskExecutor executor = taskExecutorProvider != null ? taskExecutorProvider.getIfAvailable() : null;
        if (executor != null) {
            executor.execute(AsyncContext.wrap(() -> {
                try {
                    handler.handle(record);
                } catch (Throwable t) {
                    log.warn("执行 AI 审计日志外部处理器失败: {}", t.getMessage(), t);
                }
            }));
        } else {
            try {
                handler.handle(record);
            } catch (Throwable t) {
                log.warn("执行 AI 审计日志外部处理器失败: {}", t.getMessage(), t);
            }
        }
    }

    private boolean isAuditEnabled() {
        return properties != null && properties.getAudit() != null && properties.getAudit().isEnabled();
    }

    private AiAuditProperties getAuditProperties() {
        return properties != null && properties.getAudit() != null ? properties.getAudit() : new AiAuditProperties();
    }

    private long getStartTime(Map<Object, Object> attributes) {
        if (attributes != null && attributes.get(ATTR_START_TIME) instanceof Long t) {
            return t;
        }
        return System.currentTimeMillis();
    }

    private Instant getStartInstant(Map<Object, Object> attributes) {
        if (attributes != null && attributes.get(ATTR_START_INSTANT) instanceof Instant i) {
            return i;
        }
        return Instant.now();
    }

    private String resolveModelName(ChatModelRequest request, ChatModelResponse response) {
        if (response != null && response.model() != null && !response.model().isBlank()) {
            return response.model();
        }
        if (request != null && request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return "unknown";
    }

    private String extractPromptText(ChatModelRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        return request.messages().stream()
                .map(msg -> {
                    if (msg instanceof UserMessage um) {
                        return um.singleText();
                    } else if (msg instanceof SystemMessage sm) {
                        return sm.text();
                    } else if (msg instanceof AiMessage am) {
                        return am.text();
                    }
                    return msg.text();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    public static boolean isClientAbort(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable cur = t;
        while (cur != null) {
            String name = cur.getClass().getSimpleName();
            String msg = cur.getMessage();
            if ("ClientAbortException".equals(name)
                    || "AsyncRequestNotUsableException".equals(name)
                    || (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer")))) {
                return true;
            }
            if (cur instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static void safePut(Map<Object, Object> map, Object key, Object value) {
        if (map != null && key != null && value != null) {
            map.put(key, value);
        }
    }
}
