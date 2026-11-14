package io.github.atengk.boot.web.ratelimit.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 纯 JDK 17 原生单机内存令牌桶限流存储引擎
 * 基于 ConcurrentHashMap 与 ReentrantLock 细粒度并发令牌桶算法，具备 CAS 节流清理、软容量上限淘汰保护，消除野线程与 Pinning 隐患
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class InMemoryRateLimiterStore implements RateLimiterStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimiterStore.class);

    private static final int DEFAULT_MAX_CAPACITY = 100_000;
    private static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofSeconds(60);

    private final ConcurrentMap<String, TokenBucket> cache = new ConcurrentHashMap<>();
    private final int maxCapacity;
    private final long cleanIntervalMillis;
    private final Executor executor;
    private final AtomicLong lastCleanTime = new AtomicLong(0);

    public InMemoryRateLimiterStore() {
        this(DEFAULT_MAX_CAPACITY, DEFAULT_CLEAN_INTERVAL, null);
    }

    public InMemoryRateLimiterStore(int maxCapacity, Duration cleanInterval) {
        this(maxCapacity, cleanInterval, null);
    }

    public InMemoryRateLimiterStore(int maxCapacity, Duration cleanInterval, Executor executor) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        Duration interval = cleanInterval != null && !cleanInterval.isZero() && !cleanInterval.isNegative()
                ? cleanInterval : DEFAULT_CLEAN_INTERVAL;
        this.cleanIntervalMillis = interval.toMillis();
        this.executor = executor;
    }

    @Override
    public boolean tryAcquire(String key, long count, long time, TimeUnit unit) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("RateLimiter key must not be blank");
        }
        Objects.requireNonNull(unit, "TimeUnit must not be null");
        if (count <= 0 || time <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        triggerLazyCleanup(now);

        long windowMillis = unit.toMillis(time);

        TokenBucket bucket = cache.computeIfAbsent(key, k -> new TokenBucket(count, windowMillis, now));
        boolean acquired = bucket.tryAcquire(count, windowMillis, now);
        if (acquired) {
            ensureCapacityLimit(now);
        }
        return acquired;
    }

    @Override
    public void reset(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("RateLimiter key must not be blank");
        }
        cache.remove(key);
    }

    @Override
    public long getAvailablePermits(String key, long count, long time, TimeUnit unit) {
        long fullCount = RateLimiterStore.super.getAvailablePermits(key, count, time, unit);
        TokenBucket bucket = cache.get(key);
        if (bucket == null) {
            return fullCount;
        }
        long windowMillis = unit.toMillis(time);
        if (windowMillis <= 0) {
            return fullCount;
        }
        return bucket.getAvailablePermits(fullCount, windowMillis, System.currentTimeMillis());
    }

    /**
     * 清空内存中所有限流令牌桶（主要用于测试复位或运维管理）
     */
    public void clear() {
        cache.clear();
    }

    private void triggerLazyCleanup(long now) {
        long last = lastCleanTime.get();
        if (now - last > cleanIntervalMillis && lastCleanTime.compareAndSet(last, now)) {
            if (executor != null) {
                try {
                    executor.execute(this::cleanExpiredEntries);
                } catch (Exception e) {
                    log.warn("Failed to dispatch async cleanup task, executing synchronously", e);
                    cleanExpiredEntries();
                }
            } else {
                cleanExpiredEntries();
            }
        }
    }

    private void ensureCapacityLimit(long now) {
        if (cache.size() <= maxCapacity) {
            return;
        }

        // 1. 先进行一次过期扫描
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));

        // 2. 若仍然超标，按最后活跃时间淘汰最老 10% 的元素
        if (cache.size() > maxCapacity) {
            int toRemove = Math.max(1, (int) (maxCapacity * 0.1));
            cache.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> e.getValue().getLastRefillTime()))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .forEach(cache::remove);
            log.warn("RateLimiter 内存缓存达到软上限 [{}]，已主动淘汰最冷 [{}] 个元素", maxCapacity, toRemove);
        }
    }

    private void cleanExpiredEntries() {
        try {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        } catch (Exception e) {
            log.error("RateLimiter 内存缓存清理异常: {}", e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        cache.clear();
    }

    /**
     * 单个 Key 粒度的令牌桶，基于 ReentrantLock 杜绝虚拟线程 Carrier Pinning
     */
    private static class TokenBucket {
        private final ReentrantLock lock = new ReentrantLock();
        private double tokens;
        private long lastRefillTime;
        private long windowMillis;

        TokenBucket(long capacity, long windowMillis, long now) {
            this.tokens = capacity;
            this.windowMillis = windowMillis;
            this.lastRefillTime = now;
        }

        boolean tryAcquire(long capacity, long windowMillis, long now) {
            lock.lock();
            try {
                this.windowMillis = windowMillis;
                if (now > lastRefillTime) {
                    this.tokens = computeRefilledTokens(capacity, windowMillis, now);
                    this.lastRefillTime = now;
                }
                if (this.tokens >= 1.0) {
                    this.tokens -= 1.0;
                    return true;
                }
                return false;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        long getAvailablePermits(long capacity, long windowMillis, long now) {
            lock.lock();
            try {
                double effectiveTokens = computeRefilledTokens(capacity, windowMillis, now);
                return Math.max(0L, Math.min(capacity, (long) Math.floor(effectiveTokens)));
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        private double computeRefilledTokens(long capacity, long windowMillis, long now) {
            long effectiveWindow = windowMillis > 0 ? windowMillis : 1L;
            long elapsed = Math.max(0L, now - lastRefillTime);
            if (elapsed == 0L) {
                return Math.min(capacity, this.tokens);
            }
            double generated = (double) elapsed * capacity / effectiveWindow;
            return Math.min(capacity, this.tokens + generated);
        }

        boolean isExpired(long now) {
            lock.lock();
            try {
                // 超过 2 个时间窗口无请求，视为完全过期空闲
                return now - lastRefillTime > windowMillis * 2;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        long getLastRefillTime() {
            lock.lock();
            try {
                return lastRefillTime;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
