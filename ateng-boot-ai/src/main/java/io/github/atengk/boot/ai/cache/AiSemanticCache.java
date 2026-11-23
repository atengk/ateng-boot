package io.github.atengk.boot.ai.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.observability.AiPiiMasker;
import io.github.atengk.boot.ai.observability.AtengAiObservabilityListener;
import io.github.atengk.boot.ai.properties.AiAuditProperties;
import io.github.atengk.boot.ai.properties.AiCacheProperties;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AiResponseFormat;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.sanitizer.NativeJsonSchemaContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.DigestUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 精确 + 向量语义双级响应缓存门面
 * <p>
 * 强制实施复合防串话隔离键（tenantId + modelName + samplingFingerprint + systemMessageDigest），
 * 自动对工具调用、流式响应、高随机温度采样、敏感 PII 及 {@link AiContextHolder#bypassCache()} 作用域实施安全豁免，
 * 并在启用 Micrometer 时上报低基数缓存命中指标 {@code ateng.ai.cache.requests.total}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class AiSemanticCache {

    private static final Logger log = LoggerFactory.getLogger(AiSemanticCache.class);

    /**
     * Micrometer 缓存命中度量指标名称
     */
    public static final String METRIC_CACHE_REQUESTS_TOTAL = "ateng.ai.cache.requests.total";
    private static final double DEFAULT_SAMPLING_TEMPERATURE = 0.7;

    private final AiCacheStore store;
    private final AiCacheProperties properties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final EmbeddingModel directEmbeddingModel;
    private final AiAuditProperties auditProperties;
    private final AtomicLong hitCounter = new AtomicLong(0L);
    private final AtomicLong missCounter = new AtomicLong(0L);
    private final ThreadLocal<AtomicBoolean> callHitTracker = new ThreadLocal<>();
    // 使用 Object 隔离可选 micrometer-core 依赖引用，避免无 Actuator 时类加载触发 NoClassDefFoundError
    private volatile Object meterRegistry;

    public AiSemanticCache(AiCacheStore store, AiCacheProperties properties) {
        this(store, properties, null, null, null, null);
    }

    public AiSemanticCache(AiCacheStore store, AiCacheProperties properties, EmbeddingModel embeddingModel) {
        this(store, properties, null, embeddingModel, null, null);
    }

    public AiSemanticCache(AiCacheStore store,
                           AiCacheProperties properties,
                           EmbeddingModel embeddingModel,
                           Object meterRegistry,
                           AiAuditProperties auditProperties) {
        this(store, properties, null, embeddingModel, meterRegistry, auditProperties);
    }

    public AiSemanticCache(AiCacheStore store,
                           AiCacheProperties properties,
                           ObjectProvider<EmbeddingModel> embeddingModelProvider,
                           Object meterRegistry,
                           AiAuditProperties auditProperties) {
        this(store, properties, embeddingModelProvider, null, meterRegistry, auditProperties);
    }

    private AiSemanticCache(AiCacheStore store,
                            AiCacheProperties properties,
                            ObjectProvider<EmbeddingModel> embeddingModelProvider,
                            EmbeddingModel directEmbeddingModel,
                            Object meterRegistry,
                            AiAuditProperties auditProperties) {
        this.store = Objects.requireNonNull(store, "AiCacheStore 不能为空");
        this.properties = properties != null ? properties : new AiCacheProperties();
        this.embeddingModelProvider = embeddingModelProvider;
        this.directEmbeddingModel = directEmbeddingModel;
        this.meterRegistry = meterRegistry;
        this.auditProperties = auditProperties;
    }

    public void setMeterRegistry(Object meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行双级缓存检索（一级精确 Hash 匹配 -> 二级同作用域向量语义匹配）
     *
     * @param modelName 当前路由解析后的生效大模型名称
     * @param messages  完整对话消息列表
     * @return 命中结果；若关闭、豁免或未命中则返回 {@link Optional#empty()}
     */
    public Optional<AiCacheLookupResult> get(String modelName, List<ChatMessage> messages) {
        return get(modelName, messages, null, false, false);
    }

    /**
     * 执行双级缓存检索（携带采样参数、工具调用及流式豁免标记）
     *
     * @param modelName   生效模型名称
     * @param messages    完整对话消息列表
     * @param modelConfig 当前模型参数配置（用于校验 temperature 与 responseFormat 指纹）
     * @param hasTools    是否携带函数调用/MCP 工具声明
     * @param streaming   是否为流式请求
     * @return 命中结果
     */
    public Optional<AiCacheLookupResult> get(String modelName,
                                             List<ChatMessage> messages,
                                             AiModelConfig modelConfig,
                                             boolean hasTools,
                                             boolean streaming) {
        CacheRequestContext ctx = buildContextIfCacheable(modelName, messages, modelConfig, hasTools, streaming);
        if (ctx == null) {
            return Optional.empty();
        }
        if (!properties.isCachePii() && AiPiiMasker.containsPii(ctx.conversationText())) {
            log.debug("[AI-CACHE] 检测到请求含敏感 PII 信息且 cachePii=false，跳过缓存查询");
            return Optional.empty();
        }

        // 1. 一级精确 Hash 匹配
        if (properties.isExactEnabled()) {
            try {
                Optional<AiCacheEntry> exactHit = store.getExact(ctx.exactKey());
                if (exactHit.isPresent()) {
                    AiCacheEntry entry = exactHit.get();
                    recordMetric("hit", ctx.modelName(), ctx.rawTenantId());
                    return Optional.of(AiCacheLookupResult.builder()
                            .matchType(AiCacheLookupResult.MatchType.EXACT)
                            .responseText(entry.getResponseText())
                            .similarity(1.0)
                            .exactKey(entry.getExactKey())
                            .scopeKey(entry.getScopeKey())
                            .tenantId(entry.getTenantId())
                            .modelName(entry.getModelName())
                            .build());
                }
            } catch (Exception e) {
                log.warn("[AI-CACHE] 一级精确缓存查询异常，降级穿透大模型: {}", e.getMessage(), e);
            }
        }

        // 2. 二级同作用域向量语义匹配（仅当纯文本交互时启用向量检索，防止忽略多模态图像内容）
        if (properties.isSemanticEnabled() && !ctx.hasNonTextContent()) {
            EmbeddingModel embeddingModel = resolveEmbeddingModel();
            if (embeddingModel != null && StringUtils.hasText(ctx.semanticQuery())) {
                float[] queryVector = computeEmbeddingSafely(embeddingModel, ctx.semanticQuery());
                if (queryVector != null) {
                    try {
                        Optional<AiCacheLookupResult> semanticHit = store.searchSemantic(
                                ctx.scopeKey(), queryVector, properties.getSimilarityThreshold());
                        if (semanticHit.isPresent()) {
                            recordMetric("hit", ctx.modelName(), ctx.rawTenantId());
                            return semanticHit;
                        }
                    } catch (Exception e) {
                        log.warn("[AI-CACHE] 二级语义缓存检索异常，降级穿透大模型: {}", e.getMessage(), e);
                    }
                }
            }
        }

        recordMetric("miss", ctx.modelName(), ctx.rawTenantId());
        return Optional.empty();
    }

    /**
     * 将通过安全护栏核验的响应结果回写至双级缓存
     *
     * @param modelName    生效模型名称
     * @param messages     完整对话消息列表
     * @param responseText 模型响应正文
     * @return 若实际写入缓存返回 true，若触发豁免规则跳过写入返回 false
     */
    public boolean put(String modelName, List<ChatMessage> messages, String responseText) {
        return put(modelName, messages, responseText, null, false, false);
    }

    /**
     * 将通过安全护栏核验的响应结果回写至双级缓存（携带安全豁免检查）
     *
     * @param modelName    生效模型名称
     * @param messages     完整对话消息列表
     * @param responseText 模型响应正文
     * @param modelConfig  当前模型参数配置
     * @param hasTools     是否携带工具声明
     * @param streaming    是否为流式请求
     * @return 是否成功写入
     */
    public boolean put(String modelName,
                       List<ChatMessage> messages,
                       String responseText,
                       AiModelConfig modelConfig,
                       boolean hasTools,
                       boolean streaming) {
        if (!StringUtils.hasText(responseText)) {
            return false;
        }
        CacheRequestContext ctx = buildContextIfCacheable(modelName, messages, modelConfig, hasTools, streaming);
        if (ctx == null) {
            return false;
        }
        if (!properties.isCachePii()
                && (AiPiiMasker.containsPii(ctx.conversationText()) || AiPiiMasker.containsPii(responseText))) {
            log.debug("[AI-CACHE] 检测到出入参含敏感 PII 信息且 cachePii=false，跳过缓存回写");
            return false;
        }

        float[] embedding = null;
        if (properties.isSemanticEnabled() && !ctx.hasNonTextContent()) {
            EmbeddingModel embeddingModel = resolveEmbeddingModel();
            if (embeddingModel != null && StringUtils.hasText(ctx.semanticQuery())) {
                embedding = computeEmbeddingSafely(embeddingModel, ctx.semanticQuery());
            }
        }

        AiCacheEntry entry = AiCacheEntry.builder()
                .exactKey(ctx.exactKey())
                .scopeKey(ctx.scopeKey())
                .tenantId(ctx.tenantScope())
                .modelName(ctx.modelName())
                .queryText(ctx.semanticQuery())
                .responseText(responseText)
                .embedding(embedding)
                .createdAtEpochMs(System.currentTimeMillis())
                .build();

        try {
            store.put(entry, properties.getTtl());
            return true;
        } catch (Exception e) {
            log.warn("[AI-CACHE] 回写双级响应缓存失败，忽略缓存旁路降级: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 按租户标识精准驱逐全部缓存条目
     *
     * @param tenantId 租户标识
     */
    public void evictByTenant(String tenantId) {
        store.evictByTenant(tenantId);
    }

    /**
     * 清空所有缓存条目
     */
    public void clearAll() {
        store.clearAll();
    }

    /**
     * 获取当前底层存储活跃条目总数
     *
     * @return 条目数
     */
    public long size() {
        return store.size();
    }

    /**
     * 获取底层存储 SPI 实例
     *
     * @return AiCacheStore
     */
    public AiCacheStore getStore() {
        return store;
    }

    /**
     * 获取当前缓存配置属性
     *
     * @return AiCacheProperties
     */
    public AiCacheProperties getProperties() {
        return properties;
    }

    /**
     * 校验当前请求是否具备可缓存资格并构建防串话复合隔离上下文
     */
    private CacheRequestContext buildContextIfCacheable(String modelName,
                                                        List<ChatMessage> messages,
                                                        AiModelConfig modelConfig,
                                                        boolean hasTools,
                                                        boolean streaming) {
        if (!properties.isEnabled() || AiContextHolder.isBypassCache() || streaming || hasTools) {
            return null;
        }
        if (messages == null || messages.isEmpty() || containsToolMessages(messages)) {
            return null;
        }
        double temperature = (modelConfig != null && modelConfig.getTemperature() != null)
                ? modelConfig.getTemperature()
                : DEFAULT_SAMPLING_TEMPERATURE;
        if (temperature >= properties.getMaxCacheableTemperature()) {
            return null;
        }

        String rawTenant = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
        String tenantScope = StringUtils.hasText(rawTenant) ? rawTenant.trim() : "default";
        String cleanModel = StringUtils.hasText(modelName) ? modelName.trim() : AiModelRegistry.DEFAULT_MODEL_NAME;

        StringBuilder systemBuilder = new StringBuilder();
        StringBuilder conversationBuilder = new StringBuilder();
        List<String> nonSystemTurns = new ArrayList<>();
        boolean hasNonTextContent = false;

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                if (systemBuilder.length() > 0) {
                    systemBuilder.append('\n');
                }
                systemBuilder.append(sm.text() != null ? sm.text().trim() : "");
            } else {
                String role = msg.type() != null ? msg.type().name() : "UNKNOWN";
                if (msg instanceof UserMessage um && hasNonText(um)) {
                    hasNonTextContent = true;
                }
                String text = extractMessageText(msg);
                conversationBuilder.append('[').append(role).append(']').append(text).append('\n');
                if (msg instanceof UserMessage) {
                    if (StringUtils.hasText(text)) {
                        nonSystemTurns.add(text.trim());
                    }
                } else if (msg instanceof AiMessage am && StringUtils.hasText(am.text())) {
                    nonSystemTurns.add("ASSISTANT:" + am.text().trim());
                }
            }
        }

        String conversationText = conversationBuilder.toString().trim();
        if (!StringUtils.hasText(conversationText)) {
            return null;
        }

        String systemDigest = DigestUtil.sha256Hex(systemBuilder.toString());
        String samplingFingerprint = buildSamplingFingerprint(temperature, modelConfig);
        String scopeRaw = tenantScope + "|" + cleanModel + "|" + samplingFingerprint + "|" + systemDigest;
        String scopeKey = DigestUtil.sha256Hex(scopeRaw);
        String exactKey = DigestUtil.sha256Hex(scopeRaw + "|" + conversationText);
        String semanticQuery = nonSystemTurns.isEmpty() ? conversationText : String.join("\n", nonSystemTurns);

        return new CacheRequestContext(rawTenant, tenantScope, cleanModel, scopeKey, exactKey, conversationText, semanticQuery, hasNonTextContent);
    }

    private static boolean hasNonText(UserMessage userMessage) {
        if (userMessage == null || userMessage.contents() == null) {
            return false;
        }
        for (Content content : userMessage.contents()) {
            if (content != null && !(content instanceof TextContent)) {
                return true;
            }
        }
        return false;
    }

    private static String buildSamplingFingerprint(double temperature, AiModelConfig modelConfig) {
        AiResponseFormat format = (modelConfig != null && modelConfig.getResponseFormat() != null)
                ? modelConfig.getResponseFormat()
                : AiResponseFormat.TEXT;
        NativeJsonSchemaContext.SchemaSpec schemaSpec = NativeJsonSchemaContext.get();
        String schemaPart = (schemaSpec != null && StringUtils.hasText(schemaSpec.schemaJson()))
                ? "|" + DigestUtil.sha256Hex(schemaSpec.name() + ":" + schemaSpec.schemaJson())
                : "";
        return "t=" + temperature + "|fmt=" + format.name() + schemaPart;
    }

    private static boolean containsToolMessages(List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage) {
                return true;
            }
            if (msg instanceof AiMessage am && am.hasToolExecutionRequests()) {
                return true;
            }
        }
        return false;
    }

    private static String extractMessageText(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message instanceof UserMessage um) {
            if (um.hasSingleText()) {
                return um.singleText() != null ? um.singleText().trim() : "";
            }
            if (um.contents() != null && !um.contents().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Content content : um.contents()) {
                    if (content instanceof TextContent tc && tc.text() != null) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(tc.text().trim());
                    } else if (content != null) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append('[').append(content.type()).append(':').append(DigestUtil.sha256Hex(content.toString())).append(']');
                    }
                }
                return sb.toString();
            }
            return "";
        }
        if (message instanceof AiMessage am) {
            return am.text() != null ? am.text().trim() : "";
        }
        return message.toString();
    }

    private EmbeddingModel resolveEmbeddingModel() {
        if (directEmbeddingModel != null) {
            return directEmbeddingModel;
        }
        if (embeddingModelProvider != null) {
            return embeddingModelProvider.getIfAvailable();
        }
        return null;
    }

    private float[] computeEmbeddingSafely(EmbeddingModel embeddingModel, String text) {
        try {
            Response<Embedding> response = embeddingModel.embed(text);
            if (response != null && response.content() != null) {
                return response.content().vector();
            }
        } catch (Exception e) {
            log.warn("[AI-CACHE] 计算查询文本语义向量失败，已自动降级为仅精确缓存: {}", e.getMessage());
        }
        return null;
    }

    public HitTracker openHitTracker() {
        AtomicBoolean prev = callHitTracker.get();
        AtomicBoolean current = new AtomicBoolean(false);
        callHitTracker.set(current);
        return new HitTracker(current, prev);
    }

    public long getHitCount() {
        return hitCounter.get();
    }

    public long getMissCount() {
        return missCounter.get();
    }

    private void recordMetric(String outcome, String modelName, String rawTenantId) {
        if ("hit".equals(outcome)) {
            hitCounter.incrementAndGet();
            AtomicBoolean localHit = callHitTracker.get();
            if (localHit != null) {
                localHit.set(true);
            }
        } else if ("miss".equals(outcome)) {
            missCounter.incrementAndGet();
        }
        Object registry = this.meterRegistry;
        if (registry == null) {
            return;
        }
        String tenantTag = AtengAiObservabilityListener.resolveTenantTag(rawTenantId, auditProperties);
        MicrometerRecorder.record(registry, outcome, modelName, tenantTag);
    }

    private record CacheRequestContext(
            String rawTenantId,
            String tenantScope,
            String modelName,
            String scopeKey,
            String exactKey,
            String conversationText,
            String semanticQuery,
            boolean hasNonTextContent
    ) {
    }

    /**
     * 线程级单次请求命中追踪句柄（必须在 try-with-resources 中自动释放 ThreadLocal）
     */
    public final class HitTracker implements AutoCloseable {

        private final AtomicBoolean hitFlag;
        private final AtomicBoolean previous;

        private HitTracker(AtomicBoolean hitFlag, AtomicBoolean previous) {
            this.hitFlag = hitFlag;
            this.previous = previous;
        }

        public boolean isHit() {
            return hitFlag.get();
        }

        @Override
        public void close() {
            if (previous == null) {
                callHitTracker.remove();
            } else {
                callHitTracker.set(previous);
            }
        }
    }

    /**
     * 隔离 Micrometer 可选类引用，防止运行期未引入 micrometer-core 时触发 NoClassDefFoundError
     */
    private static final class MicrometerRecorder {

        private static void record(Object registryObj, String outcome, String modelName, String tenantTag) {
            if (registryObj instanceof MeterRegistry registry) {
                Counter.builder(METRIC_CACHE_REQUESTS_TOTAL)
                        .tag("outcome", outcome)
                        .tag("model", modelName)
                        .tag("tenant_id", tenantTag)
                        .description("AI 双级语义缓存请求命中统计")
                        .register(registry)
                        .increment();
            }
        }
    }
}
