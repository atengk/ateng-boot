package io.github.atengk.boot.web.ratelimit.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * 纯 JDK 17 原生单机内存令牌桶限流存储引擎
 * 基于 ConcurrentHashMap 与无锁细粒度分段并发令牌桶算法，具备软容量上限淘汰与后台低频定时清理保护，杜绝 OOM
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class InMemoryRateLimiterStore implements RateLimiterStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimiterStore.class);

    private static final int DEFAULT_MAX_CAPACITY = 10000;
    private static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofSeconds(60);

    private final ConcurrentMap<String, TokenBucket> cache = new ConcurrentHashMap<>();
    private final int maxCapacity;
    private final ScheduledExecutorService cleanupExecutor;

    public InMemoryRateLimiterStore() {
        this(DEFAULT_MAX_CAPACITY, DEFAULT_CLEAN_INTERVAL);
    }

    public InMemoryRateLimiterStore(int maxCapacity, Duration cleanInterval) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        Duration interval = cleanInterval != null && !cleanInterval.isZero() && !cleanInterval.isNegative()
                ? cleanInterval : DEFAULT_CLEAN_INTERVAL;

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ateng-ratelimit-cleaner");
            thread.setDaemon(true);
            return thread;
        });

        long intervalMillis = interval.toMillis();
        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanExpiredEntries, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean tryAcquire(String key, long count, long time, TimeUnit unit) {
        Objects.requireNonNull(key, "RateLimiter key must not be null");
        Objects.requireNonNull(unit, "TimeUnit must not be null");
        if (count <= 0 || time <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        long windowMillis = unit.toMillis(time);

        TokenBucket bucket = cache.computeIfAbsent(key, k -> new TokenBucket(count, windowMillis, now));
        boolean acquired = bucket.tryAcquire(count, windowMillis, now);
        if (acquired) {
            ensureCapacityLimit(now);
        }
        return acquired;
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
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        cache.clear();
    }

    /**
     * 单个 Key 粒度的令牌桶
     */
    private static class TokenBucket {
        private double tokens;
        private long lastRefillTime;
        private long windowMillis;

        TokenBucket(long capacity, long windowMillis, long now) {
            this.tokens = capacity;
            this.windowMillis = windowMillis;
            this.lastRefillTime = now;
        }

        synchronized boolean tryAcquire(long capacity, long windowMillis, long now) {
            this.windowMillis = windowMillis;
            long elapsed = Math.max(0, now - lastRefillTime);
            if (elapsed > 0) {
                double generated = (double) elapsed * capacity / windowMillis;
                this.tokens = Math.min(capacity, this.tokens + generated);
                this.lastRefillTime = now;
            }
            if (this.tokens >= 1.0) {
                this.tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean isExpired(long now) {
            // 超过 2 个时间窗口无请求，视为完全过期空闲
            return now - lastRefillTime > windowMillis * 2;
        }

        synchronized long getLastRefillTime() {
            return lastRefillTime;
        }
    }
}
