package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailIdempotentProperties;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 ateng-boot-core {@link ConcurrentLruCache} 的单机内存出站幂等防重守卫默认实现
 * <p>
 * 通过底层 {@code ReentrantLock} 保护下的原子 {@code computeIfAbsent} 与 TTL 惰性过期实现进程内有界幂等抢占，
 * 并支持物理发送失败后的确定性锁释放重试。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public class InMemoryMailIdempotentGuard implements MailIdempotentGuard {

    private final MailIdempotentProperties properties;
    private final ConcurrentLruCache<String, Boolean> cache;

    public InMemoryMailIdempotentGuard(MailIdempotentProperties properties) {
        this.properties = properties != null ? properties : new MailIdempotentProperties();
        int capacity = Math.max(100, this.properties.getMaxLocalCacheSize());
        Duration ttl = this.properties.getTtl();
        this.cache = new ConcurrentLruCache<>(capacity, ttl);
    }

    @Override
    public boolean tryAcquire(MailAccount account, MailMessage message, String idempotentKey) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (idempotentKey == null || idempotentKey.isBlank()) {
            return true;
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        cache.computeIfAbsent(idempotentKey, key -> {
            acquired.set(true);
            return Boolean.TRUE;
        });
        return acquired.get();
    }

    @Override
    public void releaseLock(MailAccount account, MailMessage message, String idempotentKey) {
        if (idempotentKey != null && !idempotentKey.isBlank()) {
            cache.remove(idempotentKey);
        }
    }

    @Override
    public boolean isLocked(String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            return false;
        }
        return cache.containsKey(idempotentKey);
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
