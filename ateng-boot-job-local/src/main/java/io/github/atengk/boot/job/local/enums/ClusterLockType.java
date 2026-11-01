package io.github.atengk.boot.job.local.enums;

/**
 * 集群互斥锁存储驱动类型枚举
 *
 * @author Ateng
 * @since 2026-11-01
 */
public enum ClusterLockType {

    /**
     * 自动探测模式：优先探测 Redis (StringRedisTemplate)，存在则装配 Redis 分布式互斥锁，不存在时平滑降级为单机内存互斥锁
     */
    AUTO,

    /**
     * 强制使用 Redis 分布式互斥锁（当缺少 Redis 相关依赖或配置时，初始化将快速失败阻断）
     */
    REDIS,

    /**
     * 强制使用单机 JVM 内存互斥锁（即使存在 Redis 也仅使用本地轻量内存锁）
     */
    LOCAL
}
