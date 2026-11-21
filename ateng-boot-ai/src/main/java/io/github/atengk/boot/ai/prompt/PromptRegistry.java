package io.github.atengk.boot.ai.prompt;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiPromptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 动态提示词版本路由与资产托管注册表门面
 * <p>
 * 支持按 {@code code}、精确 {@code version} 或发布标签 {@code label}（如 {@code prod} / {@code canary}）检索与渲染提示词，
 * 线程安全（基于 {@link ReentrantLock} 防虚拟线程 Pinning），并支持运行期热刷新 {@link #refresh(String)} / {@link #refreshAll()}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

    public static final String DEFAULT_PROD_LABEL = "prod";

    private final AtengAiPromptProperties properties;
    private final List<PromptProvider> providers;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, CodeBucket> buckets = new LinkedHashMap<>();
    private volatile long lastMissingRefreshEpochMs;

    public PromptRegistry() {
        this(new AtengAiPromptProperties(), Collections.emptyList(), false);
    }

    public PromptRegistry(AtengAiPromptProperties properties, List<PromptProvider> providers) {
        this(properties, providers, true);
    }

    public PromptRegistry(AtengAiPromptProperties properties, List<PromptProvider> providers, boolean loadOnInit) {
        this.properties = properties != null ? properties : new AtengAiPromptProperties();
        this.providers = providers != null ? List.copyOf(providers) : Collections.emptyList();
        if (loadOnInit && this.properties.isEnabled()) {
            refreshAll();
        }
    }

    /**
     * 编程式动态注册或更新一条提示词定义
     * <p>
     * 相同 {@code (code, version)} 将覆盖更新；若定义携带非空 {@code label}，该标签将原子迁移至新版本。
     * </p>
     *
     * @param definition 提示词定义
     */
    public void register(PromptDefinition definition) {
        Objects.requireNonNull(definition, "PromptDefinition 不能为空");
        lock.lock();
        try {
            CodeBucket bucket = buckets.computeIfAbsent(definition.getCode(), k -> new CodeBucket());
            bucket.put(definition, false);
            bucket.lastLoadedEpochMs = System.currentTimeMillis();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 根据编码解析当前生效提示词定义（优先级：prod 标签 -> latest 标签 -> 最新注册版本）
     *
     * @param code 提示词编码
     * @return 生效的 PromptDefinition
     */
    public PromptDefinition get(String code) {
        return find(code).orElseThrow(() -> new AiException(AiResultCode.AI_CONFIG_ERROR,
                "未找到指定的提示词定义: code=" + code));
    }

    /**
     * 根据编码与精确版本号或发布标签检索提示词定义
     *
     * @param code           提示词编码
     * @param versionOrLabel 版本号或发布标签（如 "1.0.0"、"prod"、"canary"）
     * @return 匹配的 PromptDefinition
     */
    public PromptDefinition get(String code, String versionOrLabel) {
        return find(code, versionOrLabel).orElseThrow(() -> new AiException(AiResultCode.AI_CONFIG_ERROR,
                "未找到指定的提示词定义: code=" + code + ", versionOrLabel=" + versionOrLabel));
    }

    /**
     * 安全查找当前生效提示词定义（不存在返回 Optional.empty）
     *
     * @param code 提示词编码
     * @return Optional<PromptDefinition>
     */
    public Optional<PromptDefinition> find(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        String cleanCode = code.trim();
        refreshIfTtlExpired(cleanCode);
        lock.lock();
        try {
            CodeBucket bucket = buckets.get(cleanCode);
            return bucket != null ? bucket.resolveDefault() : Optional.empty();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 安全查找指定版本或标签的提示词定义
     *
     * @param code           提示词编码
     * @param versionOrLabel 版本号或标签
     * @return Optional<PromptDefinition>
     */
    public Optional<PromptDefinition> find(String code, String versionOrLabel) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(versionOrLabel)) {
            return find(code);
        }
        String cleanCode = code.trim();
        String target = versionOrLabel.trim();
        refreshIfTtlExpired(cleanCode);
        lock.lock();
        try {
            CodeBucket bucket = buckets.get(cleanCode);
            return bucket != null ? bucket.resolveByVersionOrLabel(target) : Optional.empty();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 使用默认生效版本按宽松模式渲染提示词
     *
     * @param code      提示词编码
     * @param variables 变量映射
     * @return 渲染文本
     */
    public String render(String code, Map<String, ?> variables) {
        PromptDefinition def = get(code);
        return def.toTemplate().render(variables);
    }

    /**
     * 使用指定版本或标签按宽松模式渲染提示词
     *
     * @param code           提示词编码
     * @param versionOrLabel 版本号或标签
     * @param variables      变量映射
     * @return 渲染文本
     */
    public String render(String code, String versionOrLabel, Map<String, ?> variables) {
        PromptDefinition def = get(code, versionOrLabel);
        return def.toTemplate().render(variables);
    }

    /**
     * 使用默认生效版本按严格校验模式渲染提示词（缺失任何占位变量或 requiredVariables 即刻抛出 AI_INVALID_PAYLOAD）
     *
     * @param code      提示词编码
     * @param variables 变量映射
     * @return 渲染文本
     */
    public String renderStrict(String code, Map<String, ?> variables) {
        PromptDefinition def = get(code);
        return renderDefinitionStrict(def, variables);
    }

    /**
     * 使用指定版本或标签按严格校验模式渲染提示词
     *
     * @param code           提示词编码
     * @param versionOrLabel 版本号或标签
     * @param variables      变量映射
     * @return 渲染文本
     */
    public String renderStrict(String code, String versionOrLabel, Map<String, ?> variables) {
        PromptDefinition def = get(code, versionOrLabel);
        return renderDefinitionStrict(def, variables);
    }

    /**
     * 定向热刷新指定编码的提示词定义（回源拉取所有 PromptProvider 并覆盖来自提供者的版本）
     *
     * @param code 提示词编码
     */
    public void refresh(String code) {
        if (!StringUtils.hasText(code) || providers.isEmpty()) {
            return;
        }
        String cleanCode = code.trim();
        List<PromptDefinition> loaded = new ArrayList<>();
        for (PromptProvider provider : providers) {
            List<PromptDefinition> items = provider.loadByCode(cleanCode);
            if (items != null) {
                loaded.addAll(items);
            }
        }
        lock.lock();
        try {
            CodeBucket bucket = buckets.computeIfAbsent(cleanCode, k -> new CodeBucket());
            bucket.replaceProviderDefinitions(loaded);
            bucket.lastLoadedEpochMs = System.currentTimeMillis();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        log.debug("[AI-PROMPT] 定向刷新提示词定义完成 [code={}, count={}]", cleanCode, loaded.size());
    }

    /**
     * 全量热刷新所有外部 PromptProvider 提供的提示词定义
     */
    public void refreshAll() {
        if (providers.isEmpty()) {
            return;
        }
        List<PromptDefinition> allLoaded = new ArrayList<>();
        for (PromptProvider provider : providers) {
            List<PromptDefinition> items = provider.loadAll();
            if (items != null) {
                allLoaded.addAll(items);
            }
        }

        Map<String, List<PromptDefinition>> grouped = new LinkedHashMap<>();
        for (PromptDefinition def : allLoaded) {
            if (def != null) {
                grouped.computeIfAbsent(def.getCode(), k -> new ArrayList<>()).add(def);
            }
        }

        long now = System.currentTimeMillis();
        lock.lock();
        try {
            // 先清理所有已有桶中来自外部提供者的旧条目（保留纯编程式手动注册条目）
            Iterator<Map.Entry<String, CodeBucket>> it = buckets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, CodeBucket> entry = it.next();
                entry.getValue().removeProviderDefinitions();
                if (entry.getValue().isEmpty() && !grouped.containsKey(entry.getKey())) {
                    it.remove();
                }
            }
            for (Map.Entry<String, List<PromptDefinition>> entry : grouped.entrySet()) {
                CodeBucket bucket = buckets.computeIfAbsent(entry.getKey(), k -> new CodeBucket());
                for (PromptDefinition def : entry.getValue()) {
                    bucket.put(def, true);
                }
                bucket.lastLoadedEpochMs = now;
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        log.info("[AI-PROMPT] 完成提示词全量加载/刷新，共加载外部定义 {} 条", allLoaded.size());
    }

    /**
     * 获取当前注册表中所有提示词版本定义只读快照（未加载时保底返回空集合）
     *
     * @return 只读定义列表
     */
    public List<PromptDefinition> listDefinitions() {
        lock.lock();
        try {
            if (buckets.isEmpty()) {
                return Collections.emptyList();
            }
            List<PromptDefinition> result = new ArrayList<>();
            for (CodeBucket bucket : buckets.values()) {
                result.addAll(bucket.all());
            }
            return Collections.unmodifiableList(result);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String renderDefinitionStrict(PromptDefinition def, Map<String, ?> variables) {
        return def.toTemplate().renderStrict(variables, def.getRequiredVariables());
    }

    private void refreshIfTtlExpired(String code) {
        Duration ttl = properties.getCacheTtl();
        if (ttl == null || ttl.isNegative() || ttl.isZero() || providers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long ttlMs = ttl.toMillis();
        boolean expired;
        lock.lock();
        try {
            CodeBucket bucket = buckets.get(code);
            if (bucket == null) {
                expired = (now - lastMissingRefreshEpochMs) >= ttlMs;
                if (expired) {
                    lastMissingRefreshEpochMs = now;
                }
            } else {
                expired = (now - bucket.lastLoadedEpochMs) >= ttlMs;
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        if (expired) {
            refresh(code);
        }
    }

    /**
     * 单个提示词编码的多版本/多标签管理桶（仅在 Registry ReentrantLock 内访问）
     */
    private static final class CodeBucket {

        private final Map<String, PromptDefinition> versions = new LinkedHashMap<>();
        private final Set<String> providerVersions = new HashSet<>();
        private final Map<String, String> labelToVersion = new LinkedHashMap<>();
        private PromptDefinition latest;
        private long lastLoadedEpochMs;

        void put(PromptDefinition definition, boolean fromProvider) {
            String ver = definition.getVersion();
            // 若旧同版本携带某 label，先清理其旧 label 映射
            PromptDefinition existing = versions.get(ver);
            if (existing != null && StringUtils.hasText(existing.getLabel())) {
                String oldLabel = existing.getLabel().toLowerCase(Locale.ROOT);
                if (ver.equals(labelToVersion.get(oldLabel))) {
                    labelToVersion.remove(oldLabel);
                }
            }
            versions.put(ver, definition);
            if (fromProvider) {
                providerVersions.add(ver);
            } else {
                providerVersions.remove(ver);
            }
            if (StringUtils.hasText(definition.getLabel())) {
                labelToVersion.put(definition.getLabel().toLowerCase(Locale.ROOT), ver);
            }
            latest = definition;
        }

        void removeProviderDefinitions() {
            if (providerVersions.isEmpty()) {
                return;
            }
            for (String ver : new ArrayList<>(providerVersions)) {
                PromptDefinition removed = versions.remove(ver);
                if (removed != null && StringUtils.hasText(removed.getLabel())) {
                    String lbl = removed.getLabel().toLowerCase(Locale.ROOT);
                    if (ver.equals(labelToVersion.get(lbl))) {
                        labelToVersion.remove(lbl);
                    }
                }
            }
            providerVersions.clear();
            recomputeLatest();
        }

        void replaceProviderDefinitions(List<PromptDefinition> loaded) {
            removeProviderDefinitions();
            for (PromptDefinition def : loaded) {
                put(def, true);
            }
        }

        Optional<PromptDefinition> resolveDefault() {
            String prodVer = labelToVersion.get(DEFAULT_PROD_LABEL);
            if (prodVer != null && versions.containsKey(prodVer)) {
                return Optional.of(versions.get(prodVer));
            }
            String latestLabelVer = labelToVersion.get("latest");
            if (latestLabelVer != null && versions.containsKey(latestLabelVer)) {
                return Optional.of(versions.get(latestLabelVer));
            }
            return Optional.ofNullable(latest);
        }

        Optional<PromptDefinition> resolveByVersionOrLabel(String target) {
            if (versions.containsKey(target)) {
                return Optional.of(versions.get(target));
            }
            String byLabelVer = labelToVersion.get(target.toLowerCase(Locale.ROOT));
            if (byLabelVer != null && versions.containsKey(byLabelVer)) {
                return Optional.of(versions.get(byLabelVer));
            }
            return Optional.empty();
        }

        Collection<PromptDefinition> all() {
            return versions.values();
        }

        boolean isEmpty() {
            return versions.isEmpty();
        }

        private void recomputeLatest() {
            PromptDefinition last = null;
            for (PromptDefinition def : versions.values()) {
                last = def;
            }
            latest = last;
        }
    }
}
