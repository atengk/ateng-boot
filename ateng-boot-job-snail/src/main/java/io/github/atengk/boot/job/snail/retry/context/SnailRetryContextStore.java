package io.github.atengk.boot.job.snail.retry.context;

import io.github.atengk.boot.core.context.AsyncContext;

/**
 * SnailJob 远程重试四维现场快照持久化与检索策略 SPI
 *
 * <p>为分布式重试场景提供上下文快照存储抽象。默认基于内存 LRU 缓存实现，
 * 支持在多 Pod 集群中扩展接入 Redis 等分布式存储，实现跨节点保真还原。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SnailRetryContextStore {

    /**
     * 写入四维上下文快照
     *
     * @param key      快照标识键
     * @param snapshot 四维上下文快照
     */
    void put(String key, AsyncContext.ContextSnapshot snapshot);

    /**
     * 获取四维上下文快照
     *
     * @param key 快照标识键
     * @return 对应的上下文快照，未命中时返回 null
     */
    AsyncContext.ContextSnapshot get(String key);

    /**
     * 移除快照
     *
     * @param key 快照标识键
     */
    void remove(String key);

    /**
     * 清空所有快照
     */
    void clear();
}
