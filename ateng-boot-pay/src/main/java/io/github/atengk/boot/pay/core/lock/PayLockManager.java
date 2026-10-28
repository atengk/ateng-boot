package io.github.atengk.boot.pay.core.lock;

import java.time.Duration;

/**
 * 支付异步回调防重并发锁管理器接口
 * <p>
 * 遵循双轨设计：存在 Redis 基础设施时优先采用分布式锁，无 Redis 环境下自动平滑回退至基于内存的并发锁。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface PayLockManager {

    /**
     * 尝试获取防重锁
     *
     * @param lockKey    锁标识键（通常为 channel:outTradeNo）
     * @param expireTime 锁持有超时时间（防死锁自动过期）
     * @return true 获取锁成功；false 已被其他并发线程持有
     */
    boolean tryLock(String lockKey, Duration expireTime);

    /**
     * 释放防重锁
     *
     * @param lockKey 锁标识键
     */
    void unlock(String lockKey);
}
