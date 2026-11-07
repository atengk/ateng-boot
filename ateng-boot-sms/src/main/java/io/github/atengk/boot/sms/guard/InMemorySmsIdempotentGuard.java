package io.github.atengk.boot.sms.guard;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯内存短信出站发信幂等防重卫士实现
 * 适用于单机开发与无依赖单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
public class InMemorySmsIdempotentGuard implements SmsIdempotentGuard {

    private final ConcurrentHashMap<String, Long> lockMap = new ConcurrentHashMap<>();

    @Override
    public boolean acquireLock(String key, Duration window) {
        if (key == null || key.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        long expireAt = now + (window != null ? window.toMillis() : 180_000L);
        AtomicBoolean acquired = new AtomicBoolean(false);

        lockMap.compute(key, (k, existingExpire) -> {
            if (existingExpire == null || existingExpire <= now) {
                acquired.set(true);
                return expireAt;
            }
            return existingExpire;
        });

        return acquired.get();
    }

    @Override
    public void releaseLock(String key) {
        if (key != null && !key.isBlank()) {
            lockMap.remove(key);
        }
    }

    /**
     * 清理所有在存幂等锁（用于测试或运维重置）
     */
    public void clear() {
        lockMap.clear();
    }
}
