package io.github.atengk.boot.ai.memory.episodic;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.EpisodicMemoryProperties;
import io.github.atengk.boot.ai.rag.filter.DynamicFilterContextHolder;
import io.github.atengk.boot.ai.rag.store.VectorStoreFactory;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 跨会话原子化长效情景语义记忆统一治理门面 (EpisodicMemoryTemplate)
 * <p>
 * 打破传统 ChatMemory 短会话局限，提供跨会话持久化语义记忆全生命周期管理：
 * <ul>
 *   <li>完备对称门面：支持 {@link #remember} 记住、{@link #recall} 召回、{@link #forget} 遗忘与 {@link #listFacts} 事实清单全套对称 API；</li>
 *   <li>语义槽位覆写 (Upsert by Semantic Key)：以 {@code subject:predicate} 计算语义指纹槽位，写入新事实时自动精准清理旧知识，消除知识冲突与幻觉堆叠；</li>
 *   <li>多租户与用户物理隔离：元数据强置 {@code tenant_id} 与 {@code user_id}，检索时联动 {@link DynamicFilterContextHolder} 复合下推过滤表达式；</li>
 *   <li>异步非阻塞执行：支持四维上下文透传异步抽取与批量记忆入库。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class EpisodicMemoryTemplate {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryTemplate.class);

    private static final Executor DEFAULT_EXECUTOR = new ThreadPoolExecutor(
            2,
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            new NamedThreadFactory("ai-episodic-memory", true),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final EmbeddingTemplate embeddingTemplate;
    private final ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider;
    private final EmbeddingStore<TextSegment> dedicatedStore;
    private final MemoryFactExtractor extractor;
    private final EpisodicMemoryProperties properties;
    private final Executor executor;

    /**
     * 运行时快速槽位索引与缓存登记表: key 为 "tenantId:userId"，value 为 Map<slotKey, MemoryFact>
     */
    private final ConcurrentHashMap<String, Map<String, MemoryFact>> localIndex = new ConcurrentHashMap<>();

    public EpisodicMemoryTemplate(EmbeddingTemplate embeddingTemplate) {
        this(embeddingTemplate, null, null, null, null, null);
    }

    public EpisodicMemoryTemplate(EmbeddingTemplate embeddingTemplate,
                                  MemoryFactExtractor extractor,
                                  EpisodicMemoryProperties properties) {
        this(embeddingTemplate, null, null, extractor, properties, null);
    }

    public EpisodicMemoryTemplate(EmbeddingTemplate embeddingTemplate,
                                  ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
                                  MemoryFactExtractor extractor,
                                  EpisodicMemoryProperties properties,
                                  Executor executor) {
        this(embeddingTemplate, defaultStoreProvider, null, extractor, properties, executor);
    }

    public EpisodicMemoryTemplate(EmbeddingTemplate embeddingTemplate,
                                  ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
                                  EmbeddingStore<TextSegment> dedicatedStore,
                                  MemoryFactExtractor extractor,
                                  EpisodicMemoryProperties properties,
                                  Executor executor) {
        Assert.notNull(embeddingTemplate, "embeddingTemplate 不能为空");
        this.embeddingTemplate = embeddingTemplate;
        this.defaultStoreProvider = defaultStoreProvider;
        this.properties = properties != null ? properties : new EpisodicMemoryProperties();
        this.dedicatedStore = resolveDedicatedStore(dedicatedStore, this.properties);
        this.extractor = extractor;
        this.executor = executor != null ? executor : DEFAULT_EXECUTOR;
    }

    private static EmbeddingStore<TextSegment> resolveDedicatedStore(EmbeddingStore<TextSegment> explicitStore,
                                                                     EpisodicMemoryProperties properties) {
        if (explicitStore != null) {
            return explicitStore;
        }
        if (properties != null && properties.getStore() != null && properties.getStore().getType() != null) {
            return VectorStoreFactory.createEmbeddingStore(properties.getStore());
        }
        return null;
    }

    /**
     * 解析生效的底层向量数据库实例
     *
     * @return 生效的 EmbeddingStore 实例
     */
    public EmbeddingStore<TextSegment> resolveStore() {
        if (this.dedicatedStore != null) {
            return this.dedicatedStore;
        }
        if (this.defaultStoreProvider != null) {
            EmbeddingStore<TextSegment> store = this.defaultStoreProvider.getIfAvailable();
            if (store != null) {
                return store;
            }
        }
        if (this.embeddingTemplate != null) {
            return this.embeddingTemplate.resolveDefaultStore();
        }
        throw new AiException(AiResultCode.AI_CONFIG_ERROR, "未配置可用的 EmbeddingStore 向量存储库，无法执行情景记忆持久化");
    }

    /**
     * 获取绑定的原子事实抽取器
     *
     * @return MemoryFactExtractor 实例，可能为 null
     */
    public MemoryFactExtractor getExtractor() {
        return this.extractor;
    }

    // ==================== 1. 记忆写入与语义槽位覆写 (Remember & Semantic Upsert) ====================

    /**
     * 持久化单条原子事实（自动计算语义槽位键并覆写陈旧事实以消除知识冲突）
     *
     * @param fact 待记住的事实实体
     * @return 持久化后的事实业务标识 ID
     */
    public String remember(MemoryFact fact) {
        Assert.notNull(fact, "MemoryFact 事实对象不能为空");
        Assert.hasText(fact.getSubject(), "MemoryFact subject 主体不能为空");
        Assert.hasText(fact.getPredicate(), "MemoryFact predicate 谓词不能为空");

        // 1. 完备前置默认值与上下文对齐
        if (!StringUtils.hasText(fact.getId())) {
            fact.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (!StringUtils.hasText(fact.getUserId())) {
            fact.setUserId(UserContextHolder.getUserId());
        }
        if (!StringUtils.hasText(fact.getTenantId())) {
            fact.setTenantId(TenantContextHolder.getTenantId());
        }
        if (!StringUtils.hasText(fact.getStatement())) {
            fact.setStatement(fact.getSubject() + " " + fact.getPredicate() + ": " + (fact.getObject() != null ? fact.getObject() : ""));
        }
        if (fact.getTimestamp() == null || fact.getTimestamp() <= 0) {
            fact.setTimestamp(System.currentTimeMillis());
        }
        if (fact.getConfidence() == null) {
            fact.setConfidence(1.0);
        }

        String slotKey = fact.getSlotKey();
        EmbeddingStore<TextSegment> store = resolveStore();

        // 2. 语义槽位覆写 (Upsert by Semantic Key)：清理相同 tenantId, userId 与 slotKey 的旧事实
        Filter slotFilter = MetadataFilterBuilder.metadataKey("slot_key").isEqualTo(slotKey);
        if (StringUtils.hasText(fact.getUserId())) {
            slotFilter = slotFilter.and(MetadataFilterBuilder.metadataKey("user_id").isEqualTo(fact.getUserId().trim()));
        }
        if (StringUtils.hasText(fact.getTenantId())) {
            slotFilter = slotFilter.and(MetadataFilterBuilder.metadataKey("tenant_id").isEqualTo(fact.getTenantId().trim()));
        }

        try {
            store.removeAll(slotFilter);
            log.debug("[EPISODIC-MEMORY] 语义槽位覆写: 成功清理槽位 [{}] 旧事实数据", slotKey);
        } catch (Exception e) {
            log.warn("[EPISODIC-MEMORY] 槽位覆写旧事实清理受控跳过或已清理: {}", e.getMessage());
        }

        // 3. 将新事实向量化写入存储库
        TextSegment segment = fact.toTextSegment();
        String assignedId = this.embeddingTemplate.ingest(store, segment);
        if (StringUtils.hasText(assignedId)) {
            fact.setId(assignedId);
        }

        // 4. 同步更新本地快速索引
        String cacheKey = buildCacheKey(fact.getTenantId(), fact.getUserId());
        localIndex.computeIfAbsent(cacheKey, k -> new ConcurrentHashMap<>()).put(slotKey, fact);

        log.info("[EPISODIC-MEMORY] 成功持久化情景事实 [id={}, slotKey={}, statement={}]",
                fact.getId(), slotKey, fact.getStatement());
        return fact.getId();
    }

    /**
     * 批量持久化原子事实
     *
     * @param facts 事实列表
     * @return 持久化后的事实业务标识列表
     */
    public List<String> rememberAll(List<MemoryFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(facts.size());
        for (MemoryFact fact : facts) {
            if (fact != null) {
                ids.add(remember(fact));
            }
        }
        return ids;
    }

    /**
     * 异步非阻塞持久化单条原子事实 (四维上下文全链路透传)
     *
     * @param fact 事实实体
     * @return CompletableFuture 结果
     */
    public CompletableFuture<String> rememberAsync(MemoryFact fact) {
        return CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> remember(fact)),
                this.executor
        );
    }

    /**
     * 异步非阻塞批量持久化原子事实 (四维上下文全链路透传)
     *
     * @param facts 事实列表
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<String>> rememberAllAsync(List<MemoryFact> facts) {
        return CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> rememberAll(facts)),
                this.executor
        );
    }

    // ==================== 2. 对话事实抽取并自动入库 (Extract & Remember) ====================

    /**
     * 从对话文本中提炼原子事实并自动入库 (同步阻塞)
     *
     * @param conversationText 对话文本
     * @return 抽取并成功记住的事实列表
     */
    public List<MemoryFact> extractAndRemember(String conversationText) {
        ensureExtractorConfigured();
        List<MemoryFact> extracted = this.extractor.extract(conversationText);
        if (extracted != null && !extracted.isEmpty()) {
            rememberAll(extracted);
            return extracted;
        }
        return Collections.emptyList();
    }

    /**
     * 从 ChatMessage 消息列表中提炼原子事实并自动入库 (同步阻塞)
     *
     * @param messages 消息列表
     * @return 抽取并成功记住的事实列表
     */
    public List<MemoryFact> extractAndRemember(List<ChatMessage> messages) {
        ensureExtractorConfigured();
        List<MemoryFact> extracted = this.extractor.extract(messages);
        if (extracted != null && !extracted.isEmpty()) {
            rememberAll(extracted);
            return extracted;
        }
        return Collections.emptyList();
    }

    /**
     * 异步非阻塞从对话文本提炼事实并自动入库 (四维上下文全链路透传)
     *
     * @param conversationText 对话文本
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<MemoryFact>> extractAndRememberAsync(String conversationText) {
        ensureExtractorConfigured();
        return this.extractor.extractAsync(conversationText)
                .thenApply(facts -> {
                    if (facts != null && !facts.isEmpty()) {
                        rememberAll(facts);
                        return facts;
                    }
                    return Collections.emptyList();
                });
    }

    /**
     * 异步非阻塞从 ChatMessage 列表提炼事实并自动入库 (四维上下文全链路透传)
     *
     * @param messages 消息列表
     * @return CompletableFuture 结果
     */
    public CompletableFuture<List<MemoryFact>> extractAndRememberAsync(List<ChatMessage> messages) {
        ensureExtractorConfigured();
        return this.extractor.extractAsync(messages)
                .thenApply(facts -> {
                    if (facts != null && !facts.isEmpty()) {
                        rememberAll(facts);
                        return facts;
                    }
                    return Collections.emptyList();
                });
    }

    // ==================== 3. 语义记忆召回 (Recall with Physical Isolation) ====================

    /**
     * 依据当前用户与租户上下文执行语义记忆召回 (默认 Top-K 与阈值)
     *
     * @param query 查询文本或当前用户问询
     * @return 命中的 MemoryFact 清单 (保底返回非空集合)
     */
    public List<MemoryFact> recall(String query) {
        return recall(query, this.properties.getMaxRecallResults());
    }

    /**
     * 依据当前用户与租户上下文执行语义记忆召回 (指定 Top-K)
     *
     * @param query      查询文本
     * @param maxResults 最大召回条数
     * @return 命中的 MemoryFact 清单
     */
    public List<MemoryFact> recall(String query, int maxResults) {
        return recall(query, maxResults, this.properties.getMinScore());
    }

    /**
     * 依据当前用户与租户上下文执行语义记忆召回 (指定 Top-K 与最低相似度阈值)
     *
     * @param query      查询文本
     * @param maxResults 最大召回条数
     * @param minScore   相似度阈值
     * @return 命中的 MemoryFact 清单
     */
    public List<MemoryFact> recall(String query, int maxResults, Double minScore) {
        return recall(query, null, maxResults, minScore);
    }

    /**
     * 显式指定用户与租户边界执行语义记忆召回 (核心物理隔离入口)
     *
     * @param query      查询文本
     * @param userId     目标用户标识 (若传空则从 UserContextHolder 读取)
     * @param maxResults 最大召回条数
     * @param minScore   相似度阈值
     * @return 命中的 MemoryFact 清单
     */
    public List<MemoryFact> recall(String query, String userId, int maxResults, Double minScore) {
        if (!StringUtils.hasText(query)) {
            return Collections.emptyList();
        }

        String effectiveUserId = StringUtils.hasText(userId) ? userId.trim() : UserContextHolder.getUserId();
        int effectiveMax = maxResults > 0 ? maxResults : this.properties.getMaxRecallResults();
        Double effectiveMinScore = minScore != null ? minScore : this.properties.getMinScore();

        // 1. 构建用户级物理隔离 Filter
        Filter userFilter = StringUtils.hasText(effectiveUserId)
                ? MetadataFilterBuilder.metadataKey("user_id").isEqualTo(effectiveUserId)
                : null;

        // 2. 结合 DynamicFilterContextHolder 自动复合下推租户隔离条件与线程级动态过滤
        Filter effectiveFilter = DynamicFilterContextHolder.resolveFilter(userFilter);

        // 3. 执行向量检索
        EmbeddingStore<TextSegment> store = resolveStore();
        List<EmbeddingMatch<TextSegment>> matches = this.embeddingTemplate.search(
                store,
                query.trim(),
                effectiveMax,
                effectiveMinScore,
                effectiveFilter
        );

        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 反向构建 MemoryFact 契约对象
        List<MemoryFact> facts = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            MemoryFact fact = MemoryFact.fromMatch(match);
            if (fact != null) {
                facts.add(fact);
            }
        }
        return facts;
    }

    // ==================== 4. 记忆对称清理与遗忘 (Forget & Evict) ====================

    /**
     * 根据事实全局唯一标识执行物理遗忘 (对称销毁)
     *
     * @param factId 事实业务标识
     */
    public void forget(String factId) {
        if (!StringUtils.hasText(factId)) {
            return;
        }
        String trimmedId = factId.trim();
        EmbeddingStore<TextSegment> store = resolveStore();
        try {
            store.remove(trimmedId);
        } catch (Exception ignored) {
        }
        try {
            Filter idFilter = MetadataFilterBuilder.metadataKey("id").isEqualTo(trimmedId);
            store.removeAll(idFilter);
        } catch (Exception ignored) {
        }

        // 清理本地索引
        localIndex.values().forEach(slotMap -> slotMap.values().removeIf(f -> trimmedId.equals(f.getId())));
        log.info("[EPISODIC-MEMORY] 成功依据 factId 物理遗忘事实 [factId={}]", trimmedId);
    }

    /**
     * 根据语义槽位键遗忘当前上下文用户的事实 (如 "user:preferred_language")
     *
     * @param slotKey 语义槽位键
     */
    public void forgetBySlot(String slotKey) {
        forgetBySlot(null, slotKey);
    }

    /**
     * 根据用户标识与语义槽位键执行物理遗忘
     *
     * @param userId  目标用户标识 (若传空则从 UserContextHolder 读取)
     * @param slotKey 语义槽位键
     */
    public void forgetBySlot(String userId, String slotKey) {
        if (!StringUtils.hasText(slotKey)) {
            return;
        }
        String trimmedSlot = slotKey.trim().toLowerCase();
        String effectiveUserId = StringUtils.hasText(userId) ? userId.trim() : UserContextHolder.getUserId();
        String effectiveTenantId = TenantContextHolder.getTenantId();

        Filter filter = MetadataFilterBuilder.metadataKey("slot_key").isEqualTo(trimmedSlot);
        if (StringUtils.hasText(effectiveUserId)) {
            filter = filter.and(MetadataFilterBuilder.metadataKey("user_id").isEqualTo(effectiveUserId));
        }
        if (StringUtils.hasText(effectiveTenantId) && !TenantContextHolder.isIgnoreTenant()) {
            filter = filter.and(MetadataFilterBuilder.metadataKey("tenant_id").isEqualTo(effectiveTenantId));
        }

        try {
            resolveStore().removeAll(filter);
            log.info("[EPISODIC-MEMORY] 成功依据槽位遗忘事实 [userId={}, slotKey={}]", effectiveUserId, trimmedSlot);
        } catch (Exception e) {
            log.warn("[EPISODIC-MEMORY] 槽位遗忘物理清理异常: {}", e.getMessage());
        }

        String cacheKey = buildCacheKey(effectiveTenantId, effectiveUserId);
        Map<String, MemoryFact> slotMap = localIndex.get(cacheKey);
        if (slotMap != null) {
            slotMap.remove(trimmedSlot);
        }
    }

    /**
     * 遗忘目标用户的所有情景记忆事实
     *
     * @param userId 目标用户标识
     */
    public void forgetAll(String userId) {
        String effectiveUserId = StringUtils.hasText(userId) ? userId.trim() : UserContextHolder.getUserId();
        if (!StringUtils.hasText(effectiveUserId)) {
            return;
        }
        String effectiveTenantId = TenantContextHolder.getTenantId();
        Filter filter = MetadataFilterBuilder.metadataKey("user_id").isEqualTo(effectiveUserId);
        if (StringUtils.hasText(effectiveTenantId) && !TenantContextHolder.isIgnoreTenant()) {
            filter = filter.and(MetadataFilterBuilder.metadataKey("tenant_id").isEqualTo(effectiveTenantId));
        }

        try {
            resolveStore().removeAll(filter);
            log.info("[EPISODIC-MEMORY] 成功遗忘目标用户所有情景事实 [userId={}]", effectiveUserId);
        } catch (Exception e) {
            log.warn("[EPISODIC-MEMORY] 遗忘用户全量事实异常: {}", e.getMessage());
        }

        String cacheKey = buildCacheKey(effectiveTenantId, effectiveUserId);
        localIndex.remove(cacheKey);
    }

    // ==================== 5. 事实清单查询 (List Facts) ====================

    /**
     * 查询当前上下文用户的所有情景记忆事实清单
     *
     * @return 事实清单
     */
    public List<MemoryFact> listFacts() {
        return listFacts(null, 50);
    }

    /**
     * 查询指定用户的情景记忆事实清单
     *
     * @param userId 目标用户标识
     * @return 事实清单
     */
    public List<MemoryFact> listFacts(String userId) {
        return listFacts(userId, 50);
    }

    /**
     * 查询指定用户的情景记忆事实清单 (带最大限制)
     *
     * @param userId 目标用户标识
     * @param limit  最大查询条数
     * @return 事实清单 (保底返回非空集合)
     */
    public List<MemoryFact> listFacts(String userId, int limit) {
        String effectiveUserId = StringUtils.hasText(userId) ? userId.trim() : UserContextHolder.getUserId();
        String effectiveTenantId = TenantContextHolder.getTenantId();
        int effectiveLimit = limit > 0 ? limit : 50;

        String cacheKey = buildCacheKey(effectiveTenantId, effectiveUserId);
        Map<String, MemoryFact> slotMap = localIndex.get(cacheKey);
        if (slotMap != null && !slotMap.isEmpty()) {
            return slotMap.values().stream()
                    .sorted((a, b) -> Long.compare(
                            b.getTimestamp() != null ? b.getTimestamp() : 0L,
                            a.getTimestamp() != null ? a.getTimestamp() : 0L))
                    .limit(effectiveLimit)
                    .toList();
        }

        // 缓存未命中时回退至向量存储过滤召回
        Filter filter = StringUtils.hasText(effectiveUserId)
                ? MetadataFilterBuilder.metadataKey("user_id").isEqualTo(effectiveUserId)
                : null;
        filter = DynamicFilterContextHolder.resolveFilter(filter);

        try {
            List<EmbeddingMatch<TextSegment>> matches = this.embeddingTemplate.search(
                    resolveStore(),
                    "user profile background facts and preferences",
                    effectiveLimit,
                    0.0,
                    filter
            );
            if (matches == null || matches.isEmpty()) {
                return Collections.emptyList();
            }
            List<MemoryFact> facts = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                MemoryFact fact = MemoryFact.fromMatch(match);
                if (fact != null) {
                    facts.add(fact);
                }
            }
            return facts;
        } catch (Exception e) {
            log.debug("[EPISODIC-MEMORY] listFacts 检索回退返回空: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void ensureExtractorConfigured() {
        if (this.extractor == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "MemoryFactExtractor 事实抽取器未配置，请确认是否启用了 ChatLanguageModel 或配置了 ateng.boot.ai.model.api-key");
        }
    }

    private static String buildCacheKey(String tenantId, String userId) {
        return (tenantId != null ? tenantId.trim() : "") + ":" + (userId != null ? userId.trim() : "");
    }
}
