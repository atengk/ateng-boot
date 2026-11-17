package io.github.atengk.boot.flyway.observability;

import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 可观测组件通用 TTL 缓存辅助器
 * <p>
 * 基于 {@link ReentrantLock} 双重检查锁实现并发安全的快照缓存，避免 JDK 21+ 虚拟线程 Carrier Pinning，
 * 并通过可选版本校验在全局迁移摘要更新后自动失效陈旧快照，防止并发探查竞态。
 * </p>
 *
 * @param <T> 缓存值类型
 * @author Ateng
 * @since 2026-11-17
 */
final class ObservabilityTtlCache<T> {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final AtengFlywayProperties properties;
    private final LongSupplier versionSupplier;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Snapshot<T> snapshot;

    ObservabilityTtlCache(AtengFlywayProperties properties) {
        this(properties, () -> 0L);
    }

    ObservabilityTtlCache(AtengFlywayProperties properties, LongSupplier versionSupplier) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.versionSupplier = versionSupplier != null ? versionSupplier : () -> 0L;
    }

    T getOrCompute(Supplier<T> loader) {
        Duration ttl = resolveCacheTtl();
        long currentVersion = versionSupplier.getAsLong();
        Snapshot<T> current = this.snapshot;
        Instant now = Instant.now();
        if (isCacheValid(current, ttl, now, currentVersion)) {
            return current.value();
        }
        lock.lock();
        try {
            currentVersion = versionSupplier.getAsLong();
            current = this.snapshot;
            now = Instant.now();
            if (isCacheValid(current, ttl, now, currentVersion)) {
                return current.value();
            }
            long versionBeforeLoad = versionSupplier.getAsLong();
            T computed = loader.get();
            this.snapshot = new Snapshot<>(computed, Instant.now(), versionBeforeLoad);
            return computed;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    void invalidate() {
        this.snapshot = null;
    }

    private Duration resolveCacheTtl() {
        AtengFlywayProperties.ObservabilityProperties obs = properties.getObservability();
        return obs != null && obs.getHealthCacheTtl() != null ? obs.getHealthCacheTtl() : DEFAULT_TTL;
    }

    private static <T> boolean isCacheValid(Snapshot<T> snapshot, Duration ttl, Instant now, long currentVersion) {
        if (snapshot == null || snapshot.version() != currentVersion
                || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return now.isBefore(snapshot.cachedAt().plus(ttl));
    }

    private record Snapshot<T>(T value, Instant cachedAt, long version) {
    }
}
