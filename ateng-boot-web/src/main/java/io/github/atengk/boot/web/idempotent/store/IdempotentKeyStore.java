package io.github.atengk.boot.web.idempotent.store;

import java.util.concurrent.TimeUnit;

/**
 * 接口幂等键存储与分布式锁通用 SPI 契约
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface IdempotentKeyStore {

    /**
     * 尝试对指定幂等键进行原子加锁
     *
     * @param key     全局唯一幂等键
     * @param timeout 锁定持续时长
     * @param unit    时间单位
     * @return true 代表加锁成功（首个请求）；false 代表已被占用（并发重复请求）
     */
    boolean tryLock(String key, long timeout, TimeUnit unit);

    /**
     * 主动释放指定幂等键的锁（通常在业务执行发生异常时由切面调用以允许重试）
     *
     * @param key 全局唯一幂等键
     */
    void unlock(String key);
}
