package io.github.atengk.boot.ai.properties;

/**
 * 认知图检查点持久化存储类型枚举
 *
 * @author Ateng
 * @since 2026-11-03
 */
public enum CheckpointStoreType {

    /**
     * 自动探测模式：优先探测 Redis，若无可用连接则自动平滑降级为内存存储
     */
    AUTO,

    /**
     * 单机内存模式：纯内存并发集合存储，适用于单机部署或离线自洽测试沙箱
     */
    MEMORY,

    /**
     * 分布式 Redis 模式：基于 Redis 集中式存储，支持多 Pod 共享漫游与基于 TTL 的自动淘汰
     */
    REDIS
}
