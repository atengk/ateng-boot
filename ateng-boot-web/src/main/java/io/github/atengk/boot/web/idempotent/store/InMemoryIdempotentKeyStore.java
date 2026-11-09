package io.github.atengk.boot.web.idempotent.store;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯 JDK 原生单机内存幂等键存储引擎
 * 基于 ConcurrentHashMap 实现高效无锁原子抢占，结合 CAS 节流懒清理、容量软上限淘汰三级防护，杜绝野线程与 OOM
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
    private final long cleanIntervalMillis;
    private final Executor executor;
    private final AtomicLong lastCleanTime = new AtomicLong(0);

    /**
     * 默认构造器，使用默认最大容量 10,000 与默认扫描周期 60 秒
     */
    public InMemoryIdempotentKeyStore() {
        this(DEFAULT_MAX_CAPACITY, DEFAULT_CLEAN_INTERVAL, null);
    }

    /**
     * 指定最大容量与清理周期的构造器
     *
     * @param maxCapacity   内存元素最大容量软上限
     * @param cleanInterval 节流清理扫描周期
     */
    public InMemoryIdempotentKeyStore(int maxCapacity, Duration cleanInterval) {
        this(maxCapacity, cleanInterval, null);
    }

    /**
     * 支持注入受管异步任务执行器的完整构造器
     *
     * @param maxCapacity   内存元素最大容量软上限
     * @param cleanInterval 节流清理扫描周期
     * @param executor      受管异步执行器（可为 null，为 null 时在触发线程就地轻量清理）
     */
    public InMemoryIdempotentKeyStore(int maxCapacity, Duration cleanInterval, Executor executor) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        Duration interval = cleanInterval != null && !cleanInterval.isZero() && !cleanInterval.isNegative()
                ? cleanInterval : DEFAULT_CLEAN_INTERVAL;
        this.cleanIntervalMillis = interval.toMillis();
        this.executor = executor;
    }

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotent key must not be blank");
        }
        Objects.requireNonNull(unit, "TimeUnit must not be null");

        long now = System.currentTimeMillis();
        triggerLazyCleanup(now);

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
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotent key must not be blank");
        }
        cache.remove(key);
    }

    @Override
    public boolean isLocked(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotent key must not be blank");
        }
        long now = System.currentTimeMillis();
        Long expireAt = cache.get(key);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= now) {
            cache.remove(key, expireAt);
            return false;
        }
        return true;
    }

    /**
     * 清空内存中所有幂等锁键（主要用于测试复位或运维管理）
     */
    public void clear() {
        cache.clear();
    }

    /**
     * CAS 节流触发过期键清理，杜绝后台常驻未托管单线程轮询
     */
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
     * 全量过期清理
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
        cache.clear();
    }
}
