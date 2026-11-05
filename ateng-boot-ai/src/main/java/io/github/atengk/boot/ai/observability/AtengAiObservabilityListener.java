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

import java.math.BigDecimal;
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
    public static final String METRIC_COST_TOTAL = "ateng.ai.cost.total";

    private final AtengAiProperties properties;
    private final TokenCostCalculator tokenCostCalculator;
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
        this.tokenCostCalculator = new TokenCostCalculator(this.properties.getAudit());
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
        InvocationSnapshot snapshot = InvocationSnapshot.capture(responseContext.attributes());

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
        BigDecimal estimatedCost = tokenCostCalculator.calculateCost(modelName, promptTokens, completionTokens);

        if (auditProps.isLogPayload()) {
            log.info("[AI-AUDIT-SUCCESS] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}, estimated={}, cost={}, prompt='{}', response='{}'",
                    snapshot.traceId(), snapshot.tenantId(), snapshot.userId(), modelName, snapshot.durationMs(), promptTokens, completionTokens, totalTokens, estimated, estimatedCost, promptText, responseText);
        } else {
            log.info("[AI-AUDIT-SUCCESS] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}, estimated={}, cost={}",
                    snapshot.traceId(), snapshot.tenantId(), snapshot.userId(), modelName, snapshot.durationMs(), promptTokens, completionTokens, totalTokens, estimated, estimatedCost);
        }

        AiAuditRecord record = AiAuditRecord.builder()
                .traceId(snapshot.traceId())
                .userId(snapshot.userId())
                .tenantId(snapshot.tenantId())
                .modelName(modelName)
                .durationMs(snapshot.durationMs())
                .requestTime(snapshot.startInstant())
                .responseTime(snapshot.endInstant())
                .status(AiAuditRecord.STATUS_SUCCESS)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .estimated(estimated)
                .estimatedCost(estimatedCost)
                .promptText(promptText)
                .responseText(responseText)
                .metadata(Collections.emptyMap())
                .build();

        dispatchAuditLog(record);
        recordMetricsOnResponse(modelName, snapshot.tenantId(), snapshot.durationMs(), promptTokens, completionTokens, totalTokens, estimated, estimatedCost);
    }

    private void handleError(ChatModelErrorContext errorContext) {
        InvocationSnapshot snapshot = InvocationSnapshot.capture(errorContext.attributes());

        ChatModelRequest request = errorContext.request();
        ChatModelResponse partialResponse = errorContext.partialResponse();
        Throwable error = errorContext.error();

        String modelName = resolveModelName(request, partialResponse);
        String rawPrompt = extractPromptText(request);
        AiAuditProperties auditProps = getAuditProperties();
        String promptText = auditProps.isMaskPii() ? AiPiiMasker.mask(rawPrompt) : rawPrompt;

        boolean clientAbort = isClientAbort(error);
        String status = clientAbort ? AiAuditRecord.STATUS_CANCELLED : AiAuditRecord.STATUS_ERROR;
        String errorMsg = error != null ? error.getMessage() : "Unknown error";

        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        Boolean estimated = null;

        if (clientAbort) {
            String partialText = (partialResponse != null && partialResponse.aiMessage() != null)
                    ? partialResponse.aiMessage().text() : null;
            TokenUsage partialUsage = (partialResponse != null) ? partialResponse.tokenUsage() : null;
            TokenUsage estimatedUsage = FallbackTokenEstimator.estimate(
                    partialUsage != null ? partialUsage.inputTokenCount() : null,
                    partialUsage != null ? partialUsage.outputTokenCount() : null,
                    rawPrompt,
                    partialText
            );
            promptTokens = estimatedUsage.inputTokenCount();
            completionTokens = estimatedUsage.outputTokenCount();
            totalTokens = estimatedUsage.totalTokenCount();
            estimated = true;
        }

        if (clientAbort) {
            log.warn("[AI-AUDIT-CANCELLED] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, promptTokens={}, completionTokens={}, totalTokens={}, reason=客户端主动中断连接: {}",
                    snapshot.traceId(), snapshot.tenantId(), snapshot.userId(), modelName, snapshot.durationMs(), promptTokens, completionTokens, totalTokens, errorMsg);
        } else {
            log.error("[AI-AUDIT-ERROR] [traceId={}] [tenant={}] [user={}] model={}, duration={}ms, error={}",
                    snapshot.traceId(), snapshot.tenantId(), snapshot.userId(), modelName, snapshot.durationMs(), errorMsg);
        }

        AiAuditRecord.AiAuditRecordBuilder recordBuilder = AiAuditRecord.builder()
                .traceId(snapshot.traceId())
                .userId(snapshot.userId())
                .tenantId(snapshot.tenantId())
                .modelName(modelName)
                .durationMs(snapshot.durationMs())
                .requestTime(snapshot.startInstant())
                .responseTime(snapshot.endInstant())
                .status(status)
                .promptText(promptText)
                .errorMessage(errorMsg)
                .metadata(Collections.emptyMap());

        BigDecimal estimatedCost = clientAbort ? tokenCostCalculator.calculateCost(modelName, promptTokens, completionTokens) : null;
        if (clientAbort) {
            recordBuilder.promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .estimated(estimated)
                    .estimatedCost(estimatedCost);
        }

        AiAuditRecord record = recordBuilder.build();
        dispatchAuditLog(record);
        recordMetricsOnError(modelName, snapshot.tenantId(), snapshot.durationMs(), status, promptTokens, completionTokens, totalTokens, estimated, estimatedCost);
    }

    private void recordTokenMetric(MeterRegistry registry, String modelName, String tenantTag,
                                   String type, String estimatedTag, Integer count) {
        if (count != null && count > 0) {
            Counter.builder(METRIC_TOKENS_TOTAL)
                    .tag("model", modelName)
                    .tag("tenant_id", tenantTag)
                    .tag("type", type)
                    .tag("estimated", estimatedTag)
                    .description("AI 大模型 Token 累计消耗总数")
                    .register(registry)
                    .increment(count);
        }
    }

    private static String resolveTenantTag(String tenantId) {
        return (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
    }

    private void recordMetricsOnResponse(String modelName, String tenantId, long durationMs,
                                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                         boolean estimated, BigDecimal estimatedCost) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }

        String tenantTag = resolveTenantTag(tenantId);

        // 1. 请求计数器 ateng.ai.requests.total (tags: model, tenant_id, status=SUCCESS)
        Counter.builder(METRIC_REQUESTS_TOTAL)
                .tag("model", modelName)
                .tag("tenant_id", tenantTag)
                .tag("status", AiAuditRecord.STATUS_SUCCESS)
                .description("AI 大模型调用累计请求总数")
                .register(registry)
                .increment();

        // 2. 耗时直方图 ateng.ai.duration.seconds (tags: model, tenant_id)
        Timer.builder(METRIC_DURATION_SECONDS)
                .tag("model", modelName)
                .tag("tenant_id", tenantTag)
                .description("AI 大模型调用耗时直方图")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(Duration.ofMillis(durationMs));

        // 3. Token 消耗计数器 ateng.ai.tokens.total (tags: model, tenant_id, type, estimated)
        String estimatedTag = String.valueOf(estimated);
        recordTokenMetric(registry, modelName, tenantTag, "prompt", estimatedTag, promptTokens);
        recordTokenMetric(registry, modelName, tenantTag, "completion", estimatedTag, completionTokens);
        recordTokenMetric(registry, modelName, tenantTag, "total", estimatedTag, totalTokens);

        // 4. 财务成本累计指标 ateng.ai.cost.total (tags: model, tenant_id)
        recordCostMetric(registry, modelName, tenantTag, estimatedCost);
    }

    private void recordMetricsOnError(String modelName, String tenantId, long durationMs, String status,
                                      Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                      Boolean estimated, BigDecimal estimatedCost) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }

        String tenantTag = resolveTenantTag(tenantId);

        // 1. 请求计数器 ateng.ai.requests.total (tags: model, tenant_id, status [ERROR | CANCELLED])
        Counter.builder(METRIC_REQUESTS_TOTAL)
                .tag("model", modelName)
                .tag("tenant_id", tenantTag)
                .tag("status", status)
                .description("AI 大模型调用累计请求总数")
                .register(registry)
                .increment();

        // 2. 耗时直方图 ateng.ai.duration.seconds (tags: model, tenant_id)
        Timer.builder(METRIC_DURATION_SECONDS)
                .tag("model", modelName)
                .tag("tenant_id", tenantTag)
                .description("AI 大模型调用耗时直方图")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(Duration.ofMillis(durationMs));

        // 3. 若客户端中断等场景估算并记录了 Token，同步沉淀指标
        if (promptTokens != null || completionTokens != null || totalTokens != null) {
            String estimatedTag = String.valueOf(Boolean.TRUE.equals(estimated));
            recordTokenMetric(registry, modelName, tenantTag, "prompt", estimatedTag, promptTokens);
            recordTokenMetric(registry, modelName, tenantTag, "completion", estimatedTag, completionTokens);
            recordTokenMetric(registry, modelName, tenantTag, "total", estimatedTag, totalTokens);
        }

        // 4. 财务成本累计指标
        recordCostMetric(registry, modelName, tenantTag, estimatedCost);
    }

    private void recordCostMetric(MeterRegistry registry, String modelName, String tenantTag, BigDecimal estimatedCost) {
        if (estimatedCost != null && estimatedCost.compareTo(BigDecimal.ZERO) > 0) {
            Counter.builder(METRIC_COST_TOTAL)
                    .tag("model", modelName)
                    .tag("tenant_id", tenantTag)
                    .description("AI 大模型累计财务成本消耗")
                    .register(registry)
                    .increment(estimatedCost.doubleValue());
        }
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

        Runnable invokeTask = () -> {
            try {
                handler.handle(record);
            } catch (Throwable t) {
                log.warn("执行 AI 审计日志外部处理器失败: {}", t.getMessage(), t);
            }
        };

        TaskExecutor executor = taskExecutorProvider != null ? taskExecutorProvider.getIfAvailable() : null;
        if (executor != null) {
            executor.execute(AsyncContext.wrap(invokeTask));
        } else {
            invokeTask.run();
        }
    }

    private boolean isAuditEnabled() {
        return properties != null && properties.getAudit() != null && properties.getAudit().isEnabled();
    }

    private AiAuditProperties getAuditProperties() {
        return properties != null && properties.getAudit() != null ? properties.getAudit() : new AiAuditProperties();
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
        return request != null ? FallbackTokenEstimator.extractPromptText(request.messages()) : "";
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

    /**
     * 内部调用快照元数据载体，统一封装耗时与四维上下文提取，防御数据泥团与代码重复
     */
    private record InvocationSnapshot(
            Instant startInstant,
            Instant endInstant,
            long durationMs,
            String traceId,
            String userId,
            String tenantId
    ) {
        static InvocationSnapshot capture(Map<Object, Object> attributes) {
            long startTime = (attributes != null && attributes.get(ATTR_START_TIME) instanceof Long t)
                    ? t : System.currentTimeMillis();
            Instant startInstant = (attributes != null && attributes.get(ATTR_START_INSTANT) instanceof Instant i)
                    ? i : Instant.now();
            Instant endInstant = Instant.now();
            long durationMs = Math.max(0, System.currentTimeMillis() - startTime);

            String traceId = (String) (attributes != null ? attributes.get(ATTR_TRACE_ID) : MDC.get("traceId"));
            String userId = (String) (attributes != null ? attributes.get(ATTR_USER_ID) : UserContextHolder.getUserId());
            String tenantId = (String) (attributes != null ? attributes.get(ATTR_TENANT_ID) : TenantContextHolder.getTenantId());

            return new InvocationSnapshot(startInstant, endInstant, durationMs, traceId, userId, tenantId);
        }
    }
}
