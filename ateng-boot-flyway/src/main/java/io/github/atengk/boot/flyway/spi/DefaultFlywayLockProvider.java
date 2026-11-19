package io.github.atengk.boot.flyway.spi;

import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缺省迁移并发互斥锁实现
 * <p>
 * 基于按 {@code targetKey} 隔离的进程内 {@link ReentrantLock} 与引用计数回收机制实现支持超时的线程互斥，
 * 避免 JDK 21+ 虚拟线程 Carrier Pinning 与长运行多租户扩缩容下的锁映射内存泄漏；
 * 跨节点/多副本并发竞争同时委托 Flyway 原生数据库表级/咨询锁处理，亦可替换为自定义分布式锁 SPI 实现。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class DefaultFlywayLockProvider implements FlywayLockProvider {

    private static final String DEFAULT_LOCK_KEY = "default";

    private final ConcurrentHashMap<String, RefCountedLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String targetKey, Duration waitTimeout) {
        String key = normalizeKey(targetKey);
        RefCountedLock entry = locks.compute(key, (k, existing) -> {
            RefCountedLock lockEntry = existing != null ? existing : new RefCountedLock();
            lockEntry.activeCount.incrementAndGet();
            return lockEntry;
        });

        boolean acquired = false;
        try {
            if (waitTimeout == null || waitTimeout.isNegative() || waitTimeout.isZero()) {
                acquired = entry.lock.tryLock();
            } else {
                long waitNanos = waitTimeout.getSeconds() > 86_400L * 365
                        ? TimeUnit.DAYS.toNanos(365)
                        : waitTimeout.toNanos();
                acquired = entry.lock.tryLock(waitNanos, TimeUnit.NANOSECONDS);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (!acquired) {
                releaseEntryRef(key, entry);
            }
        }
    }

    @Override
    public void unlock(String targetKey) {
        String key = normalizeKey(targetKey);
        RefCountedLock entry = locks.get(key);
        if (entry == null) {
            return;
        }
        if (entry.lock.isHeldByCurrentThread()) {
            try {
                entry.lock.unlock();
            } finally {
                releaseEntryRef(key, entry);
            }
        }
    }

    /**
     * 获取当前仍驻留在锁表中的活跃目标锁数量（用于回收校验与状态观测）
     *
     * @return 活跃锁条目数
     */
    public int getTrackedLockCount() {
        return locks.size();
    }

    private void releaseEntryRef(String key, RefCountedLock entry) {
        locks.computeIfPresent(key, (k, current) -> {
            if (current != entry) {
                return current;
            }
            if (current.activeCount.decrementAndGet() <= 0
                    && !current.lock.isLocked()
                    && !current.lock.hasQueuedThreads()) {
                return null;
            }
            return current;
        });
    }

    private static String normalizeKey(String targetKey) {
        return StringUtils.hasText(targetKey) ? targetKey.trim() : DEFAULT_LOCK_KEY;
    }

    private static final class RefCountedLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger activeCount = new AtomicInteger(0);
    }
}
