package io.github.atengk.boot.pay.core.lock;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 ConcurrentHashMap 的单机内存防重锁实现
 * <p>
 * 在无分布式缓存或单体部署环境下作为绝对可靠的防重保底机制，
 * 采用时间戳原子比对防止并发死锁与重复穿透。
 * 遵循严格的防御性锁释放范式：通过 ThreadLocal 记录当前线程持有的锁令牌，
 * 在 {@link #unlock(String)} 时校验令牌一致性，杜绝因锁超时被后继线程重新获取后被原线程误删。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class InMemoryPayLockManager implements PayLockManager {

    private record LockHolder(String token, long expireAt) {}

    /**
     * 锁映射表：锁标识键 -> 锁持有者记录
     */
    private final ConcurrentHashMap<String, LockHolder> lockMap = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, String>> threadLocks = ThreadLocal.withInitial(HashMap::new);

    @Override
    public boolean tryLock(String lockKey, Duration expireTime) {
        Objects.requireNonNull(lockKey, "lockKey 不能为空");
        Duration duration = expireTime != null ? expireTime : Duration.ofSeconds(10);
        long now = System.currentTimeMillis();
        long expireAt = now + duration.toMillis();
        String token = UUID.randomUUID().toString();

        AtomicBoolean acquired = new AtomicBoolean(false);
        lockMap.compute(lockKey, (k, old) -> {
            if (old == null || old.expireAt() <= now) {
                acquired.set(true);
                return new LockHolder(token, expireAt);
            }
            return old;
        });

        if (acquired.get()) {
            threadLocks.get().put(lockKey, token);
            return true;
        }
        return false;
    }

    @Override
    public void unlock(String lockKey) {
        if (lockKey == null) {
            return;
        }
        Map<String, String> locks = threadLocks.get();
        String token = locks.remove(lockKey);
        if (locks.isEmpty()) {
            threadLocks.remove();
        }
        if (token == null) {
            // 当前线程未持有该锁，严禁误删其他线程锁
            return;
        }

        lockMap.computeIfPresent(lockKey, (k, old) -> Objects.equals(old.token(), token) ? null : old);
    }

    /**
     * 清理所有锁记录（主要用于单元测试隔离与内存重置）
     */
    public void clear() {
        lockMap.clear();
        threadLocks.remove();
    }
}
