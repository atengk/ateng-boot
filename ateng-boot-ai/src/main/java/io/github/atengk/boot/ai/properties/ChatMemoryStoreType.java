package io.github.atengk.boot.ai.properties;

/**
 * AI 会话记忆存储类型枚举
 *
 * @author Ateng
 * @since 2026-11-03
 */
public enum ChatMemoryStoreType {

    /**
     * 单机内存并发容器存储
     */
    IN_MEMORY,

    /**
     * 基于 Redis 的分布式存储（支持跨实例漫游与 TTL 自动过期淘汰）
     */
    REDIS,

    /**
     * 自动探测模式：当容器中存在 Redis 相关 Bean 时优先使用 Redis，否则自动平滑降级至内存存储
     */
    AUTO
}
