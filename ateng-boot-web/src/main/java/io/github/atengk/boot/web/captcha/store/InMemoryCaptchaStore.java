package io.github.atengk.boot.web.captcha.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 纯 JDK 17 原生单机内存图形验证码存储实现
 * 基于 {@link ConcurrentHashMap} 原子删除语义保障并发单次消费，配合 {@link ReentrantLock} 淘汰与 CAS 节流惰性清理杜绝虚拟线程 Pinning 与内存溢出
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class InMemoryCaptchaStore implements CaptchaStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCaptchaStore.class);

    private static final int DEFAULT_MAX_CAPACITY = 100_000;
    private static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofSeconds(60);

    private final ConcurrentMap<String, CaptchaEntry> cache = new ConcurrentHashMap<>();
    private final ReentrantLock maintenanceLock = new ReentrantLock();
    private final AtomicLong lastCleanTime = new AtomicLong(0);
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    private final int maxCapacity;
    private final long cleanIntervalMillis;
    private final Executor executor;

    public InMemoryCaptchaStore() {
        this(DEFAULT_MAX_CAPACITY, DEFAULT_CLEAN_INTERVAL, null);
    }

    public InMemoryCaptchaStore(int maxCapacity, Duration cleanInterval) {
        this(maxCapacity, cleanInterval, null);
    }

    public InMemoryCaptchaStore(int maxCapacity, Duration cleanInterval, Executor executor) {
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        Duration interval = cleanInterval != null && !cleanInterval.isZero() && !cleanInterval.isNegative()
                ? cleanInterval
                : DEFAULT_CLEAN_INTERVAL;
        this.cleanIntervalMillis = interval.toMillis();
        this.executor = executor;
    }

    @Override
    public void save(String key, String answer, Duration ttl) {
        validateKey(key);
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("Captcha answer must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Captcha ttl must be positive");
        }

        long now = System.currentTimeMillis();
        triggerLazyCleanup(now);

        long expireAt = now + Math.max(1L, ttl.toMillis());
        cache.put(key, new CaptchaEntry(answer, expireAt, sequenceGenerator.incrementAndGet()));
        ensureCapacityLimit(now);
    }

    @Override
    public String getAndDelete(String key) {
        validateKey(key);
        long now = System.currentTimeMillis();
        triggerLazyCleanup(now);

        CaptchaEntry entry = cache.remove(key);
        if (entry == null || entry.isExpired(now)) {
            return null;
        }
        return entry.answer();
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        cache.remove(key);
    }

    /**
     * 返回当前缓存条目总数（含待清理项，主要用于运维观测与测试验证）
     *
     * @return 条目数
     */
    public int size() {
        return cache.size();
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Captcha key must not be blank");
        }
    }

    private void triggerLazyCleanup(long now) {
        long last = lastCleanTime.get();
        if (now - last > cleanIntervalMillis && lastCleanTime.compareAndSet(last, now)) {
            if (executor != null) {
                try {
                    executor.execute(this::cleanExpiredEntries);
                } catch (Exception ex) {
                    log.warn("Failed to dispatch async captcha cleanup task, executing synchronously", ex);
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
        maintenanceLock.lock();
        try {
            if (cache.size() <= maxCapacity) {
                return;
            }
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            if (cache.size() > maxCapacity) {
                int toRemove = Math.max(1, cache.size() - maxCapacity);
                cache.entrySet().stream()
                        .sorted(Comparator.comparingLong(e -> e.getValue().sequence()))
                        .limit(toRemove)
                        .map(Map.Entry::getKey)
                        .forEach(cache::remove);
                log.warn("Captcha 内存缓存达到软上限 [{}]，已主动淘汰最旧 [{}] 个元素", maxCapacity, toRemove);
            }
        } finally {
            if (maintenanceLock.isHeldByCurrentThread()) {
                maintenanceLock.unlock();
            }
        }
    }

    private void cleanExpiredEntries() {
        if (!maintenanceLock.tryLock()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        } catch (Exception ex) {
            log.error("Captcha 内存缓存清理异常: {}", ex.getMessage(), ex);
        } finally {
            if (maintenanceLock.isHeldByCurrentThread()) {
                maintenanceLock.unlock();
            }
        }
    }

    @Override
    public void destroy() {
        cache.clear();
    }

    private record CaptchaEntry(String answer, long expireAtMillis, long sequence) {
        boolean isExpired(long nowMillis) {
            return nowMillis >= expireAtMillis;
        }
    }
}
