package io.github.atengk.boot.web.idempotent.store;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯 JDK 原生单机内存幂等键存储引擎
 * 基于 ConcurrentHashMap 实现高效无锁原子抢占，结合惰性检查、容量软上限淘汰与低频定时驱逐三级防护，杜绝 OOM
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class InMemoryIdempotentKeyStore implements IdempotentKeyStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotentKeyStore.class);

    private static final int DEFAULT_MAX_CAPACITY = 10000;
    private static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofSeconds(60);

    private final ConcurrentMap<String, Long> cache = new ConcurrentHashMap<>();
    private final int maxCapacity;
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * 默认构造器，使用默认最大容量 10,000 与默认定时扫描周期 60 秒
     */
    public InMemoryIdempotentKeyStore() {
        this(DEFAULT_MAX_CAPACITY, DEFAULT_CLEAN_INTERVAL);
    }

    /**
     * 指定最大容量与清理周期的完整构造器
     *
     * @param maxCapacity   内存元素最大容量软上限
     * @param cleanInterval 定时全量清理扫描周期
     */
    public InMemoryIdempotentKeyStore(int maxCapacity, Duration cleanInterval) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        Duration interval = cleanInterval != null && !cleanInterval.isZero() && !cleanInterval.isNegative()
                ? cleanInterval : DEFAULT_CLEAN_INTERVAL;

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("ateng-idempotent-cleaner", true));

        long intervalMillis = interval.toMillis();
        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanExpiredEntries, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Objects.requireNonNull(key, "Idempotent key must not be null");
        Objects.requireNonNull(unit, "TimeUnit must not be null");

        long now = System.currentTimeMillis();
        long expireAt = now + unit.toMillis(timeout);
        AtomicBoolean locked = new AtomicBoolean(false);

        cache.compute(key, (k, existing) -> {
            if (existing == null || existing <= now) {
                locked.set(true);
                return expireAt;
            }
            return existing;
        });

        if (locked.get()) {
            ensureCapacityLimit(now);
            return true;
        }
        return false;
    }

    @Override
    public void unlock(String key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    /**
     * 软容量上限防爆检查与淘汰
     */
    private void ensureCapacityLimit(long now) {
        if (cache.size() <= maxCapacity) {
            return;
        }

        // 1. 先进行一次全量过期扫描
        cache.entrySet().removeIf(entry -> entry.getValue() <= now);

        // 2. 若依然超过软上限，按过期时间升序排序剔除最早过期的溢出条目 (Soft LRU Guard)
        int excess = cache.size() - maxCapacity;
        if (excess > 0) {
            cache.entrySet().stream()
                    .sorted(Comparator.comparingLong(Map.Entry::getValue))
                    .limit(excess)
                    .forEach(entry -> cache.remove(entry.getKey(), entry.getValue()));
            log.warn("InMemoryIdempotentKeyStore reached capacity threshold [{}], evicted [{}] excess entries",
                    maxCapacity, excess);
        }
    }

    /**
     * 定时全量过期清理
     */
    private void cleanExpiredEntries() {
        try {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue() <= now);
        } catch (Exception e) {
            log.error("Failed to clean expired idempotent keys in memory store", e);
        }
    }

    @Override
    public void destroy() {
        try {
            cleanupExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }
}
