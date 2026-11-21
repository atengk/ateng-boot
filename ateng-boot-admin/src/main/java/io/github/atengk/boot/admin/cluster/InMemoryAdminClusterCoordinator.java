package io.github.atengk.boot.admin.cluster;

import io.github.atengk.boot.admin.silence.SilenceRule;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于纯内存并发结构的单机版集群协调器默认实现（零外部依赖，虚拟线程安全无 synchronized）
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class InMemoryAdminClusterCoordinator implements AdminClusterCoordinator {

    private record LockEntry(String ownerId, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> dispatchedAlerts = new ConcurrentHashMap<>();
    private final Map<String, SilenceRule> silenceRules = new ConcurrentHashMap<>();

    private final ReentrantLock lockGuard = new ReentrantLock();
    private final ReentrantLock dedupGuard = new ReentrantLock();
    private final ReentrantLock silenceGuard = new ReentrantLock();

    public InMemoryAdminClusterCoordinator() {
        this(Clock.systemUTC());
    }

    public InMemoryAdminClusterCoordinator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean tryAcquireLock(String lockKey, String ownerId, Duration ttl) {
        if (!StringUtils.hasText(lockKey) || !StringUtils.hasText(ownerId)) {
            return false;
        }
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(30) : ttl;
        lockGuard.lock();
        try {
            Instant now = clock.instant();
            LockEntry existing = locks.get(lockKey);
            if (existing != null && now.isBefore(existing.expiresAt())) {
                return false;
            }
            locks.put(lockKey, new LockEntry(ownerId, now.plus(effectiveTtl)));
            return true;
        } finally {
            lockGuard.unlock();
        }
    }

    @Override
    public void releaseLock(String lockKey, String ownerId) {
        if (!StringUtils.hasText(lockKey) || !StringUtils.hasText(ownerId)) {
            return;
        }
        lockGuard.lock();
        try {
            LockEntry existing = locks.get(lockKey);
            if (existing != null && ownerId.equals(existing.ownerId())) {
                locks.remove(lockKey);
            }
        } finally {
            lockGuard.unlock();
        }
    }

    @Override
    public boolean tryMarkAlertDispatched(String dedupKey, Duration ttl) {
        if (!StringUtils.hasText(dedupKey)) {
            return true;
        }
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(60) : ttl;
        dedupGuard.lock();
        try {
            Instant now = clock.instant();
            dispatchedAlerts.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
            Instant existingExpiry = dispatchedAlerts.get(dedupKey);
            if (existingExpiry != null && now.isBefore(existingExpiry)) {
                return false;
            }
            dispatchedAlerts.put(dedupKey, now.plus(effectiveTtl));
            return true;
        } finally {
            dedupGuard.unlock();
        }
    }

    @Override
    public void saveSilenceRule(SilenceRule rule) {
        if (rule == null || !StringUtils.hasText(rule.getId())) {
            return;
        }
        silenceGuard.lock();
        try {
            Instant now = clock.instant();
            evictExpiredSilenceRulesLocked(now);
            if (!rule.isExpired(now)) {
                silenceRules.put(rule.getId(), rule);
            }
        } finally {
            silenceGuard.unlock();
        }
    }

    @Override
    public void deleteSilenceRule(String ruleId) {
        if (!StringUtils.hasText(ruleId)) {
            return;
        }
        silenceGuard.lock();
        try {
            silenceRules.remove(ruleId);
        } finally {
            silenceGuard.unlock();
        }
    }

    @Override
    public List<SilenceRule> listSilenceRules() {
        silenceGuard.lock();
        try {
            Instant now = clock.instant();
            evictExpiredSilenceRulesLocked(now);
            List<SilenceRule> result = new ArrayList<>(silenceRules.values());
            result.sort(Comparator.comparing(
                    SilenceRule::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));
            return List.copyOf(result);
        } finally {
            silenceGuard.unlock();
        }
    }

    @Override
    public int pruneExpiredSilenceRules() {
        silenceGuard.lock();
        try {
            return evictExpiredSilenceRulesLocked(clock.instant());
        } finally {
            silenceGuard.unlock();
        }
    }

    /**
     * 使用最新远端活跃规则全量刷新本地兜底缓存，避免跨节点删除后降级时复活已撤销规则
     */
    void replaceSilenceRules(List<SilenceRule> activeRules) {
        silenceGuard.lock();
        try {
            silenceRules.clear();
            if (activeRules != null) {
                Instant now = clock.instant();
                for (SilenceRule rule : activeRules) {
                    if (rule != null && StringUtils.hasText(rule.getId()) && !rule.isExpired(now)) {
                        silenceRules.put(rule.getId(), rule);
                    }
                }
            }
        } finally {
            silenceGuard.unlock();
        }
    }

    private int evictExpiredSilenceRulesLocked(Instant now) {
        int removed = 0;
        var iterator = silenceRules.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() == null || entry.getValue().isExpired(now)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }
}
