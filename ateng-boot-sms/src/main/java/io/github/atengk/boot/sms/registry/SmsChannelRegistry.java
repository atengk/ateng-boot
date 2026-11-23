package io.github.atengk.boot.sms.registry;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsRegistryProperties;
import io.github.atengk.boot.sms.properties.SmsTemplateProperties;
import io.github.atengk.boot.sms.support.BoundedLruMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 多命名短信渠道注册中心
 * 统一纳管 ateng.boot.sms.channels.* 命名的多云、多账号渠道配置
 * 支持静态渠道注册注销、{@link SmsChannelProvider} 按需动态加载（受有界 LRU 与 TTL 约束）及渠道驱逐联动通知
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsChannelRegistry {

    private final Map<String, SmsChannel> channelMap = new ConcurrentHashMap<>();
    @Getter
    private final SmsRegistryProperties registryProperties;
    private final SmsChannelProvider channelProvider;
    private final Clock clock;
    private final BoundedLruMap<String, CachedDynamicChannel> dynamicCache;
    private final List<Consumer<String>> evictListeners = new CopyOnWriteArrayList<>();

    public SmsChannelRegistry() {
        this((SmsProperties) null, null, Clock.systemDefaultZone());
    }

    public SmsChannelRegistry(SmsProperties properties) {
        this(properties, null, Clock.systemDefaultZone());
    }

    public SmsChannelRegistry(SmsProperties properties, SmsChannelProvider channelProvider) {
        this(properties, channelProvider, Clock.systemDefaultZone());
    }

    public SmsChannelRegistry(SmsProperties properties, SmsChannelProvider channelProvider, Clock clock) {
        this.registryProperties = (properties != null && properties.getRegistry() != null)
                ? properties.getRegistry()
                : new SmsRegistryProperties();
        this.channelProvider = channelProvider;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        int maxSize = this.registryProperties.getDynamicCacheMaxSize() > 0
                ? this.registryProperties.getDynamicCacheMaxSize()
                : 1024;
        this.dynamicCache = new BoundedLruMap<>(maxSize);
        if (properties != null && properties.getChannels() != null) {
            initFromProperties(properties.getChannels());
        }
    }

    /**
     * 基于配置属性初始化渠道映射
     *
     * @param channelsConfig 配置属性中的渠道映射
     */
    public void initFromProperties(Map<String, SmsChannelProperties> channelsConfig) {
        if (channelsConfig == null || channelsConfig.isEmpty()) {
            return;
        }
        channelsConfig.forEach((name, prop) -> {
            if (prop != null) {
                Map<String, List<String>> paramOrders = new HashMap<>();
                if (prop.getTemplates() != null) {
                    for (Map.Entry<String, SmsTemplateProperties> entry : prop.getTemplates().entrySet()) {
                        if (entry.getValue() != null && entry.getValue().getParamOrder() != null) {
                            paramOrders.put(entry.getKey(), entry.getValue().getParamOrder());
                        }
                    }
                }
                SmsSupplier effectiveSupplier = prop.getSupplier();
                if (org.springframework.util.StringUtils.hasText(prop.getProviderCode())
                        && effectiveSupplier == SmsSupplier.MOCK
                        && !"mock".equalsIgnoreCase(prop.getProviderCode().trim())) {
                    SmsSupplier inferred = SmsSupplier.fromCode(prop.getProviderCode());
                    effectiveSupplier = inferred != null ? inferred : SmsSupplier.CUSTOM;
                }
                SmsChannel channel = SmsChannel.builder()
                        .name(name)
                        .supplier(effectiveSupplier)
                        .providerCode(prop.getProviderCode())
                        .accessKeyId(prop.getAccessKeyId())
                        .accessKeySecret(prop.getAccessKeySecret())
                        .signName(prop.getSignName())
                        .signId(prop.getSignId())
                        .extCode(prop.getExtCode())
                        .appId(prop.getAppId())
                        .region(prop.getRegion())
                        .endpoint(prop.getEndpoint())
                        .failoverChannel(prop.getFailoverChannel())
                        .timeout(prop.getTimeout())
                        .defaultTemplateId(prop.getDefaultTemplateId())
                        .statusCallback(prop.getStatusCallback())
                        .weight(prop.getWeight() > 0 ? prop.getWeight() : 1)
                        .maxRetries(prop.getMaxRetries())
                        .retryInterval(prop.getRetryInterval() != null ? prop.getRetryInterval() : java.time.Duration.ofSeconds(1))
                        .internationalEnabled(prop.isInternationalEnabled())
                        .defaultRegionCode(prop.getDefaultRegionCode())
                        .maxQuota(prop.getMaxQuota())
                        .quotaResetCycle(prop.getQuotaResetCycle() != null
                                ? prop.getQuotaResetCycle()
                                : io.github.atengk.boot.sms.model.SmsQuotaResetCycle.DAILY)
                        .proxy(prop.getProxy() != null ? prop.getProxy().copy() : null)
                        .extras(prop.getExtras() != null ? Collections.unmodifiableMap(new LinkedHashMap<>(prop.getExtras())) : Collections.emptyMap())
                        .templates(prop.getTemplates() != null ? Collections.unmodifiableMap(new LinkedHashMap<>(prop.getTemplates())) : Collections.emptyMap())
                        .templateParamOrders(paramOrders)
                        .build();
                register(channel);
            }
        });
    }

    /**
     * 动态注册或更新短信渠道
     *
     * @param channel 短信渠道配置
     */
    public void register(SmsChannel channel) {
        BusinessAssert.notNull(channel, "注册的短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getName(), "短信渠道名称不能为空");
        BusinessAssert.isTrue(channel.getSupplier() != null || org.springframework.util.StringUtils.hasText(channel.getProviderCode()),
                "短信渠道对应的服务商 SmsSupplier 与 providerCode 不能同时为空");

        String name = channel.getName().trim();
        channelMap.put(name, channel);
        dynamicCache.remove(name);
        notifyEvictListeners(name);
        if (log.isInfoEnabled()) {
            log.info("[SmsChannelRegistry] 成功注册/更新短信渠道 [名称: {}, 驱动编码: {}, 服务商: {}, 容灾备用: {}]",
                    channel.getName(), channel.resolveProviderCode(), channel.getSupplier(), channel.getFailoverChannel());
        }
    }

    /**
     * 动态注销指定名称的短信渠道
     *
     * @param channelName 渠道名称
     * @return 被注销的 SmsChannel 对象，若不存在则返回 null
     */
    public SmsChannel unregister(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        String name = channelName.trim();
        SmsChannel removed = channelMap.remove(name);
        dynamicCache.remove(name);
        notifyEvictListeners(name);
        if (removed != null && log.isInfoEnabled()) {
            log.info("[SmsChannelRegistry] 成功注销短信渠道 [名称: {}]", channelName);
        }
        return removed;
    }

    /**
     * 获取指定名称的渠道配置
     * 优先命中静态注册渠道；未命中且名称为 "mock" 返回默认内存 Mock 渠道；否则通过 SmsChannelProvider 动态加载并进入有界 TTL LRU 缓存
     *
     * @param channelName 渠道名称
     * @return SmsChannel 渠道对象，未找到返回 null
     */
    public SmsChannel get(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        String name = channelName.trim();
        SmsChannel channel = channelMap.get(name);
        if (channel != null) {
            return channel;
        }
        // 若名称为 mock 且未显式配置，保底构造内存 Mock 渠道
        if ("mock".equalsIgnoreCase(name)) {
            return SmsChannel.builder()
                    .name("mock")
                    .supplier(SmsSupplier.MOCK)
                    .signName("MOCK")
                    .build();
        }
        return resolveDynamicChannel(name);
    }

    /**
     * 判断是否已注册或可动态供给指定名称的渠道
     *
     * @param channelName 渠道名称
     * @return true 表示已存在或可加载
     */
    public boolean contains(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return false;
        }
        String name = channelName.trim();
        if (channelMap.containsKey(name) || "mock".equalsIgnoreCase(name)) {
            return true;
        }
        return resolveDynamicChannel(name) != null;
    }

    /**
     * 精确驱逐指定动态渠道缓存
     *
     * @param channelName 渠道名称
     */
    public void evictDynamic(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        dynamicCache.remove(channelName.trim());
    }

    /**
     * 全量清空动态渠道缓存
     */
    public void evictAllDynamic() {
        dynamicCache.clear();
    }

    /**
     * 获取当前动态渠道缓存条目数
     *
     * @return 动态缓存数量
     */
    public int dynamicSize() {
        return dynamicCache.size();
    }

    /**
     * 注册渠道变更/驱逐联动回调监听器
     *
     * @param listener 回调接收发生变更或注销的渠道名
     */
    public void addEvictListener(Consumer<String> listener) {
        if (listener != null) {
            evictListeners.add(listener);
        }
    }

    private SmsChannel resolveDynamicChannel(String name) {
        if (channelProvider == null) {
            return null;
        }
        if (!registryProperties.isDynamicCacheEnabled()) {
            return channelProvider.loadChannel(name);
        }

        long ttlMillis = resolveTtlMillis();
        long now = clock.millis();
        SmsChannel cached = dynamicCache.withLock(view -> {
            CachedDynamicChannel entry = view.get(name);
            if (entry == null) {
                return null;
            }
            if (isEntryExpired(entry, now, ttlMillis)) {
                view.remove(name);
                return null;
            }
            return entry.channel();
        });
        if (cached != null) {
            return cached;
        }

        SmsChannel loaded = channelProvider.loadChannel(name);
        if (loaded == null) {
            return null;
        }
        long storeTime = clock.millis();
        dynamicCache.put(name, new CachedDynamicChannel(loaded, storeTime),
                entry -> isEntryExpired(entry, storeTime, ttlMillis));
        return loaded;
    }

    private long resolveTtlMillis() {
        Duration ttl = registryProperties.getDynamicCacheTtl();
        return ttl != null && !ttl.isNegative() && !ttl.isZero() ? ttl.toMillis() : 0L;
    }

    private boolean isEntryExpired(CachedDynamicChannel entry, long nowMillis, long ttlMillis) {
        return entry == null || (ttlMillis > 0 && (nowMillis - entry.loadedAtMillis()) >= ttlMillis);
    }

    private void notifyEvictListeners(String channelName) {
        for (Consumer<String> listener : evictListeners) {
            try {
                listener.accept(channelName);
            } catch (Exception ex) {
                log.warn("[SmsChannelRegistry] 执行渠道驱逐回调异常 -> 渠道: {}, 错误: {}", channelName, ex.getMessage());
            }
        }
    }

    private record CachedDynamicChannel(SmsChannel channel, long loadedAtMillis) {
    }

    /**
     * 获取所有已注册渠道的不可变视图
     *
     * @return 渠道名称 -> SmsChannel 映射
     */
    public Map<String, SmsChannel> getChannels() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(channelMap));
    }

    /**
     * 获取所有已注册渠道的列表
     *
     * @return 渠道列表
     */
    public List<SmsChannel> getAll() {
        return new ArrayList<>(channelMap.values());
    }

    /**
     * 获取当前已注册渠道数量
     *
     * @return 渠道数量
     */
    public int size() {
        return channelMap.size();
    }

    /**
     * 清空所有渠道（测试隔离清理）
     */
    public void clear() {
        channelMap.clear();
        dynamicCache.clear();
    }
}
