package io.github.atengk.boot.redis.cache.twolevel.l1;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.util.Assert;

import java.time.Duration;

/**
 * 基于 Caffeine 的高性能 L1 本地内存缓存实现
 * 依托 W-TinyLFU 高命中淘汰算法与高性能并发环形缓冲区
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class CaffeineL1MemoryCache implements L1MemoryCache {

    private final Cache<Object, Object> cache;

    public CaffeineL1MemoryCache(long maxSize, Duration ttl) {
        Assert.isTrue(maxSize > 0, "maxSize 必须大于 0");
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxSize);
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            builder.expireAfterWrite(ttl);
        }
        this.cache = builder.build();
    }

    @Override
    public Object get(Object key) {
        if (key == null) {
            return null;
        }
        return cache.getIfPresent(key);
    }

    @Override
    public void put(Object key, Object value) {
        if (key == null) {
            return;
        }
        if (value != null) {
            cache.put(key, value);
        } else {
            cache.invalidate(key);
        }
    }

    @Override
    public void evict(Object key) {
        if (key == null) {
            return;
        }
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }
}
