package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.support.BoundedLruMap;

import java.time.Clock;
import java.time.Duration;

/**
 * 纯内存短信出站发信幂等防重卫士实现
 * 基于有界 LRU 与 ReentrantLock 保障单机开发与高频恶意探测下内存稳定可控
 *
 * @author Ateng
 * @since 2026-11-07
 */
public class InMemorySmsIdempotentGuard implements SmsIdempotentGuard {

    private final BoundedLruMap<String, Long> lockMap;
    private final Clock clock;

    public InMemorySmsIdempotentGuard(int maxEntries, Clock clock) {
        this.lockMap = new BoundedLruMap<>(maxEntries);
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    public InMemorySmsIdempotentGuard(int maxEntries) {
        this(maxEntries, Clock.systemDefaultZone());
    }

    public InMemorySmsIdempotentGuard(Clock clock) {
        this(BoundedLruMap.DEFAULT_MAX_ENTRIES, clock);
    }

    public InMemorySmsIdempotentGuard() {
        this(BoundedLruMap.DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
    }

    @Override
    public boolean acquireLock(String key, Duration window) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String normalizedKey = key.trim();
        long now = clock.millis();
        long expireAt = now + (window != null ? window.toMillis() : 180_000L);

        return lockMap.withLock(view -> {
            Long existingExpire = view.get(normalizedKey);
            if (existingExpire == null || existingExpire <= now) {
                view.put(normalizedKey, expireAt, exp -> exp == null || exp <= now);
                return true;
            }
            return false;
        });
    }

    @Override
    public void releaseLock(String key) {
        if (key != null && !key.isBlank()) {
            lockMap.remove(key.trim());
        }
    }

    /**
     * 清理所有在存幂等锁（用于测试或运维重置）
     */
    public void clear() {
        lockMap.clear();
    }

    /**
     * 获取当前在存幂等键数量（用于容量验证与监控探测）
     *
     * @return 条目数
     */
    public int size() {
        return lockMap.size();
    }
}
