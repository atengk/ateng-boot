package io.github.atengk.boot.job.snail.retry.context;

import io.github.atengk.boot.core.context.AsyncContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SnailJob 分布式重试四维现场快照注册中心
 *
 * <p>用于在本地抛出异常并上报 SnailJob 服务端时，暂存包含 MDC traceId、User 操作人、TenantId 租户与 IgnoreTenant 标志的
 * 四维上下文快照。当重试流量通过远程或本地重新触发执行时，精准恢复原始业务现场，彻底消除跨租户与线程脏读隐患。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class SnailRetryContextRegistry {

    private static final int MAX_ENTRIES = 4096;

    private static final Map<String, AsyncContext.ContextSnapshot> CACHE =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AsyncContext.ContextSnapshot> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private SnailRetryContextRegistry() {
    }

    /**
     * 注册四维上下文快照
     *
     * @param key 场景与方法或幂等唯一标识
     * @param snapshot 上下文快照
     */
    public static synchronized void put(String key, AsyncContext.ContextSnapshot snapshot) {
        if (key != null && snapshot != null) {
            CACHE.put(key, snapshot);
        }
    }

    /**
     * 获取四维上下文快照
     *
     * @param key 标识键
     * @return 对应的上下文快照，未命中返回 null
     */
    public static synchronized AsyncContext.ContextSnapshot get(String key) {
        return key != null ? CACHE.get(key) : null;
    }

    /**
     * 移除快照
     *
     * @param key 标识键
     */
    public static synchronized void remove(String key) {
        if (key != null) {
            CACHE.remove(key);
        }
    }

    /**
     * 清理所有快照缓存
     */
    public static synchronized void clear() {
        CACHE.clear();
    }
}
