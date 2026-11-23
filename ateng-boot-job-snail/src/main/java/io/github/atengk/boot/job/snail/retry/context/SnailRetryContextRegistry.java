package io.github.atengk.boot.job.snail.retry.context;

import io.github.atengk.boot.core.context.AsyncContext;

/**
 * SnailJob 分布式重试四维现场快照注册中心门面
 *
 * <p>用于在本地抛出异常并上报 SnailJob 服务端时，暂存包含 MDC traceId、User 操作人、TenantId 租户与 IgnoreTenant 标志的
 * 四维上下文快照。底层委托给 {@link SnailRetryContextStore} 策略接口实现，支持内存 LRU 与分布式持久化存储扩展。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class SnailRetryContextRegistry {

    private static volatile SnailRetryContextStore store = new InMemorySnailRetryContextStore();

    private SnailRetryContextRegistry() {
    }

    /**
     * 设置当前生效的上下文存储策略
     *
     * @param customStore 自定义存储策略
     */
    public static void setStore(SnailRetryContextStore customStore) {
        store = customStore != null ? customStore : new InMemorySnailRetryContextStore();
    }

    /**
     * 获取当前生效的上下文存储策略
     *
     * @return 当前存储策略
     */
    public static SnailRetryContextStore getStore() {
        return store;
    }

    /**
     * 注册四维上下文快照
     *
     * @param key      场景与方法或幂等唯一标识
     * @param snapshot 上下文快照
     */
    public static void put(String key, AsyncContext.ContextSnapshot snapshot) {
        store.put(key, snapshot);
    }

    /**
     * 获取四维上下文快照
     *
     * @param key 标识键
     * @return 对应的上下文快照，未命中返回 null
     */
    public static AsyncContext.ContextSnapshot get(String key) {
        return store.get(key);
    }

    /**
     * 移除快照
     *
     * @param key 标识键
     */
    public static void remove(String key) {
        store.remove(key);
    }

    /**
     * 清理所有快照缓存
     */
    public static void clear() {
        store.clear();
    }
}
