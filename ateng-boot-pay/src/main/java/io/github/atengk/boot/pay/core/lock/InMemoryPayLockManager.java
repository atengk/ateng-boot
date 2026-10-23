package io.github.atengk.boot.pay.core.lock;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 ConcurrentHashMap 的单机内存防重锁实现
 * <p>
 * 在无分布式缓存或单体部署环境下作为绝对可靠的防重保底机制，
 * 采用时间戳原子比对防止并发死锁与重复穿透。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class InMemoryPayLockManager implements PayLockManager {

    /**
     * 锁映射表：锁标识键 -> 过期时间戳（毫秒）
     */
    private final ConcurrentHashMap<String, Long> lockMap = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String lockKey, Duration expireTime) {
        Objects.requireNonNull(lockKey, "lockKey 不能为空");
        Duration duration = expireTime != null ? expireTime : Duration.ofSeconds(10);
        long now = System.currentTimeMillis();
        long expireAt = now + duration.toMillis();

        AtomicBoolean acquired = new AtomicBoolean(false);
        lockMap.compute(lockKey, (k, oldExpireAt) -> {
            if (oldExpireAt == null || oldExpireAt <= now) {
                acquired.set(true);
                return expireAt;
            }
            return oldExpireAt;
        });

        return acquired.get();
    }

    @Override
    public void unlock(String lockKey) {
        if (lockKey != null) {
            lockMap.remove(lockKey);
        }
    }

    /**
     * 清理所有锁记录（主要用于单元测试隔离与内存重置）
     */
    public void clear() {
        lockMap.clear();
    }
}
