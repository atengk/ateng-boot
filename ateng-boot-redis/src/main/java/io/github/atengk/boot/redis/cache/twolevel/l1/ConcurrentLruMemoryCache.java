package io.github.atengk.boot.redis.cache.twolevel.l1;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 原生 JVM 内存 LRU 缓存实现（缺省降级兜底）
 * 当类路径未引入 Caffeine 依赖时，自动平滑退化为此内置实现，受 maxSize 严格限制以杜绝 OOM
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class ConcurrentLruMemoryCache implements L1MemoryCache {

    private final long maxSize;
    private final long ttlMillis;
    private final Map<Object, CacheEntry> map;
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwl.writeLock();

    public ConcurrentLruMemoryCache(long maxSize, Duration ttl) {
        Assert.isTrue(maxSize > 0, "maxSize 必须大于 0");
        this.maxSize = maxSize;
        this.ttlMillis = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl.toMillis() : -1L;
        int initialCapacity = (int) Math.min(maxSize, 128);
        this.map = new LinkedHashMap<>(initialCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, CacheEntry> eldest) {
                return size() > ConcurrentLruMemoryCache.this.maxSize;
            }
        };
    }

    @Override
    public Object get(Object key) {
        if (key == null) {
            return null;
        }
        writeLock.lock();
        try {
            CacheEntry entry = map.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {
                map.remove(key);
                return null;
            }
            return entry.value;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (key == null) {
            return;
        }
        long expireAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : Long.MAX_VALUE;
        writeLock.lock();
        try {
            map.put(key, new CacheEntry(value, expireAt));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void evict(Object key) {
        if (key == null) {
            return;
        }
        writeLock.lock();
        try {
            map.remove(key);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public long size() {
        writeLock.lock();
        try {
            return map.size();
        } finally {
            writeLock.unlock();
        }
    }

    private static class CacheEntry {
        final Object value;
        final long expireAtMillis;

        CacheEntry(Object value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired() {
            return expireAtMillis != Long.MAX_VALUE && System.currentTimeMillis() > expireAtMillis;
        }
    }
}
