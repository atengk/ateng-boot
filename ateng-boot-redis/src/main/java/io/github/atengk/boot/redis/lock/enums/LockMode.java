package io.github.atengk.boot.redis.lock.enums;

/**
 * 分布式锁类型枚举
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum LockMode {

    /**
     * 可重入排他锁（默认模式）
     */
    REENTRANT,

    /**
     * 公平锁（严格遵循先进先出 FIFO 顺序获取）
     */
    FAIR,

    /**
     * 读写锁之读锁（共享读锁，读读并发，读写互斥）
     */
    READ,

    /**
     * 读写锁之写锁（排他写锁，写写互斥，读写互斥）
     */
    WRITE
}
