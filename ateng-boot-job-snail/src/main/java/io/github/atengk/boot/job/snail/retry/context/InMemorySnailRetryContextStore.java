package io.github.atengk.boot.job.snail.retry.context;

import io.github.atengk.boot.core.context.AsyncContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于内存 LRU 缓存与惰性 TTL 过期的无 Pinning {@link SnailRetryContextStore} 实现
 *
 * <p>全面采用 {@link ReentrantLock} 替代 {@code synchronized}，兼容 JDK 21+ 虚拟线程防 Carrier Pinning；
 * 内置访问序 LRU 容量上限淘汰与基于 {@link Instant} 的 TTL 过期清理，防御内存泄露与堆内存溢出。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class InMemorySnailRetryContextStore implements SnailRetryContextStore {

    public static final int DEFAULT_MAX_ENTRIES = 4096;
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final ReentrantLock lock = new ReentrantLock();
    private final int maxEntries;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<String, CacheEntry> cache;

    public InMemorySnailRetryContextStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL, Clock.systemUTC());
    }

    public InMemorySnailRetryContextStore(int maxEntries) {
        this(maxEntries, DEFAULT_TTL, Clock.systemUTC());
    }

    public InMemorySnailRetryContextStore(int maxEntries, Duration ttl) {
        this(maxEntries, ttl, Clock.systemUTC());
    }

    public InMemorySnailRetryContextStore(int maxEntries, Duration ttl, Clock clock) {
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        this.ttl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : DEFAULT_TTL;
        this.clock = Objects.requireNonNullElseGet(clock, Clock::systemUTC);
        this.cache = new LinkedHashMap<>(Math.min(this.maxEntries, 16), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > InMemorySnailRetryContextStore.this.maxEntries;
            }
        };
    }

    @Override
    public void put(String key, AsyncContext.ContextSnapshot snapshot) {
        if (key == null || snapshot == null) {
            return;
        }
        Instant now = clock.instant();
        Instant expireAt = now.plus(ttl);
        lock.lock();
        try {
            if (cache.size() >= maxEntries) {
                purgeExpiredLocked(now);
            }
            cache.put(key, new CacheEntry(snapshot, expireAt));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public AsyncContext.ContextSnapshot get(String key) {
        if (key == null) {
            return null;
        }
        Instant now = clock.instant();
        lock.lock();
        try {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (!now.isBefore(entry.expireAt())) {
                cache.remove(key);
                return null;
            }
            return entry.snapshot();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void remove(String key) {
        if (key == null) {
            return;
        }
        lock.lock();
        try {
            cache.remove(key);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 清理过期条目并返回当前有效缓存条目数
     *
     * @return 当前未过期条目总数
     */
    public int size() {
        Instant now = clock.instant();
        lock.lock();
        try {
            purgeExpiredLocked(now);
            return cache.size();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void purgeExpiredLocked(Instant now) {
        Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (!now.isBefore(entry.getValue().expireAt())) {
                iterator.remove();
            }
        }
    }

    private record CacheEntry(AsyncContext.ContextSnapshot snapshot, Instant expireAt) {
    }
}
