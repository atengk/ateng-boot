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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI 大模型调用全维可观测审计与云原生度量监听器
 * <p>
 * 基于 LangChain4j 原生 ChatModelListener SPI 规范，在不引入重型依赖的前提下透明拦截大模型请求生命周期，
 * 记录调用耗时、时间戳、模型元数据，还原四维上下文，实施 PII 正则脱敏，接入 Micrometer 黄金度量体系，
 * 并支持流式保底 Token 估算与非阻塞异步审计日志扩展点回调。
 * </p>
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

    /**
     * Micrometer 标准度量指标名称常量
     */
    public static final String METRIC_REQUESTS_TOTAL = "ateng.ai.requests.total";
    public static final String METRIC_DURATION_SECONDS = "ateng.ai.duration.seconds";
    public static final String METRIC_TOKENS_TOTAL = "ateng.ai.tokens.total";

    private final AtengAiProperties properties;
    private final ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider;
    private final ObjectProvider<TaskExecutor> taskExecutorProvider;
    private volatile MeterRegistry meterRegistry;

    public AtengAiObservabilityListener(AtengAiProperties properties,
                                        ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider,
                                        ObjectProvider<TaskExecutor> taskExecutorProvider) {
        this(properties, auditLogHandlerProvider, taskExecutorProvider, (MeterRegistry) null);
    }

    public AtengAiObservabilityListener(AtengAiProperties properties,
                                        ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider,
                                        ObjectProvider<TaskExecutor> taskExecutorProvider,
                                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties, auditLogHandlerProvider, taskExecutorProvider, meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null);
    }

    public AtengAiObservabilityListener(AtengAiProperties properties,
                                        ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider,
                                        ObjectProvider<TaskExecutor> taskExecutorProvider,
                                        MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.auditLogHandlerProvider = auditLogHandlerProvider;
        this.taskExecutorProvider = taskExecutorProvider;
        this.meterRegistry = meterRegistry;
    }

    public AtengAiObservabilityListener(AtengAiProperties properties,
                                        MeterRegistry meterRegistry) {
        this(properties, null, null, meterRegistry);
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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

        String rawPrompt = extractPromptText(request);
        String rawResponse = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;

        Integer promptTokens;
        Integer completionTokens;
        Integer totalTokens;
        boolean estimated;

        if (usage != null && usage.inputTokenCount() != null && usage.outputTokenCount() != null) {
            promptTokens = usage.inputTokenCount();
            completionTokens = usage.outputTokenCount();
            totalTokens = usage.totalTokenCount() != null ? usage.totalTokenCount() : (promptTokens + completionTokens);
            estimated = false;
        } else {
            TokenUsage estimatedUsage = FallbackTokenEstimator.estimate(
                    usage != null ? usage.inputTokenCount() : null,
                    usage != null ? usage.outputTokenCount() : null,
                    rawPrompt,
                    rawResponse
            );
            promptTokens = estimatedUsage.inputTokenCount();
            completionTokens = estimatedUsage.outputTokenCount();
            totalTokens = estimatedUsage.totalTokenCount();
            estimated = true;
        }

        AiAuditProperties auditProps = getAuditProperties();
        String promptText = auditProps.isMaskPii() ? AiPiiMasker.mask(rawPrompt) : rawPrompt;
        String responseText = auditProps.isMaskPii() ? AiPiiMasker.mask(rawResponse) : rawResponse;

        String traceId = (String) (attributes != null ? attributes.get(ATTR_TRACE_ID) : MDC.get("traceId"));
        String userId = (String) (attributes != null ? attributes.get(ATTR_USER_ID) : UserContextHolder.getUserId());
        String tenantId = (String) (attributes != null ? attributes.get(ATTR_TENANT_ID) : TenantContextHolder.getTenantId());

        if (auditProps.isLogPayload()) {
            log.info("[AI-AUDIT-SUCCESS] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}, estimated={}, prompt='{}', response='{}'",
                    traceId, tenantId, userId, modelName, durationMs, promptTokens, completionTokens, totalTokens, estimated, promptText, responseText);
        } else {
            log.info("[AI-AUDIT-SUCCESS] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}, estimated={}",
                    traceId, tenantId, userId, modelName, durationMs, promptTokens, completionTokens, totalTokens, estimated);
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
                .estimated(estimated)
                .promptText(promptText)
                .responseText(responseText)
                .metadata(Collections.emptyMap())
                .build();

        dispatchAuditLog(record);
        recordMetricsOnResponse(modelName, durationMs, promptTokens, completionTokens, totalTokens, estimated);
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
        recordMetricsOnError(modelName, durationMs, status);
    }

    private void recordMetricsOnResponse(String modelName, long durationMs,
                                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                         boolean estimated) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }

        // 1. 请求计数器 ateng.ai.requests.total (tags: model, status=SUCCESS)
        Counter.builder(METRIC_REQUESTS_TOTAL)
                .tag("model", modelName)
                .tag("status", "SUCCESS")
                .description("AI 大模型调用累计请求总数")
                .register(registry)
                .increment();

        // 2. 耗时直方图 ateng.ai.duration.seconds (tags: model)
        Timer.builder(METRIC_DURATION_SECONDS)
                .tag("model", modelName)
                .description("AI 大模型调用耗时直方图")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(Duration.ofMillis(durationMs));

        // 3. Token 消耗计数器 ateng.ai.tokens.total (tags: model, type, estimated)
        String estimatedTag = String.valueOf(estimated);
        if (promptTokens != null && promptTokens > 0) {
            Counter.builder(METRIC_TOKENS_TOTAL)
                    .tag("model", modelName)
                    .tag("type", "prompt")
                    .tag("estimated", estimatedTag)
                    .description("AI 大模型 Token 累计消耗总数")
                    .register(registry)
                    .increment(promptTokens);
        }
        if (completionTokens != null && completionTokens > 0) {
            Counter.builder(METRIC_TOKENS_TOTAL)
                    .tag("model", modelName)
                    .tag("type", "completion")
                    .tag("estimated", estimatedTag)
                    .description("AI 大模型 Token 累计消耗总数")
                    .register(registry)
                    .increment(completionTokens);
        }
        if (totalTokens != null && totalTokens > 0) {
            Counter.builder(METRIC_TOKENS_TOTAL)
                    .tag("model", modelName)
                    .tag("type", "total")
                    .tag("estimated", estimatedTag)
                    .description("AI 大模型 Token 累计消耗总数")
                    .register(registry)
                    .increment(totalTokens);
        }
    }

    private void recordMetricsOnError(String modelName, long durationMs, String status) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }

        // 1. 请求计数器 ateng.ai.requests.total (tags: model, status [ERROR | CANCELLED])
        Counter.builder(METRIC_REQUESTS_TOTAL)
                .tag("model", modelName)
                .tag("status", status)
                .description("AI 大模型调用累计请求总数")
                .register(registry)
                .increment();

        // 2. 耗时直方图 ateng.ai.duration.seconds (tags: model)
        Timer.builder(METRIC_DURATION_SECONDS)
                .tag("model", modelName)
                .description("AI 大模型调用耗时直方图")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(Duration.ofMillis(durationMs));
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

    @SuppressWarnings("deprecation")
    private String extractPromptText(ChatModelRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        return request.messages().stream()
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

    /**
     * 判断异常链是否属于客户端主动断开或取消
     *
     * @param t 抛出的异常
     * @return 若判定为客户端主动中断返回 true，否则返回 false
     */
    public static boolean isClientAbort(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable cur = t;
        while (cur != null) {
            String simpleName = cur.getClass().getSimpleName();
            String msg = cur.getMessage();
            if ("ClientAbortException".equals(simpleName)
                    || "AsyncRequestNotUsableException".equals(simpleName)
                    || "AsyncRequestTimeoutException".equals(simpleName)
                    || cur instanceof java.util.concurrent.CancellationException
                    || "CancellationException".equals(simpleName)
                    || cur instanceof InterruptedException
                    || "InterruptedException".equals(simpleName)) {
                return true;
            }
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("connection was aborted")
                        || lower.contains("stream was closed")
                        || lower.contains("client has disconnected")
                        || lower.contains("request was cancelled")) {
                    return true;
                }
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
