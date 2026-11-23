package io.github.atengk.boot.job.local.lock;

import io.github.atengk.boot.core.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单机内存轻量集群互斥锁默认实现
 * <p>
 * 提供零外部中间件依赖的单机保底实现；支持注入共享锁存储模拟多节点并发竞争、租约超时防死锁与属主防误删防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobClusterLockProvider implements JobClusterLockProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalJobClusterLockProvider.class);

    private final String nodeId;
    private final ConcurrentMap<String, LockEntry> lockStore;

    public LocalJobClusterLockProvider() {
        this(resolveDefaultNodeId(), new ConcurrentHashMap<>());
    }

    public LocalJobClusterLockProvider(String nodeId) {
        this(nodeId, new ConcurrentHashMap<>());
    }

    public LocalJobClusterLockProvider(String nodeId, ConcurrentMap<String, LockEntry> sharedLockStore) {
        this.nodeId = nodeId != null && !nodeId.isBlank() ? nodeId.trim() : resolveDefaultNodeId();
        this.lockStore = sharedLockStore != null ? sharedLockStore : new ConcurrentHashMap<>();
    }

    @Override
    public JobLockHandle tryLock(String jobName, Duration leaseTime) {
        if (jobName == null || jobName.isBlank()) {
            return JobLockHandle.failed(jobName);
        }

        Duration effectiveLease = (leaseTime != null && !leaseTime.isZero() && !leaseTime.isNegative())
                ? leaseTime
                : Duration.ofMinutes(5);

        String owner = nodeId + "@" + Thread.currentThread().getName() + ":" + IdUtil.fastSimpleUUID();
        Instant now = Instant.now();
        Instant expireAt = now.plus(effectiveLease);
        LockEntry newEntry = new LockEntry(owner, expireAt);

        AtomicBoolean acquired = new AtomicBoolean(false);
        lockStore.compute(jobName.trim(), (key, existing) -> {
            if (existing == null || existing.isExpired()) {
                acquired.set(true);
                return newEntry;
            }
            log.debug("Cluster lock for job [{}] is currently held by [{}] until [{}]",
                    key, existing.getLockOwner(), existing.getLeaseExpireAt());
            return existing;
        });

        if (acquired.get()) {
            log.debug("Node [{}] acquired cluster lock for job [{}] with lease [{}]", nodeId, jobName, effectiveLease);
            return JobLockHandle.success(jobName.trim(), owner, expireAt, now);
        }
        return JobLockHandle.failed(jobName.trim());
    }

    @Override
    public boolean unlock(JobLockHandle handle) {
        if (handle == null || !handle.isAcquired() || handle.getLockKey() == null || handle.getLockOwner() == null) {
            return false;
        }

        String jobName = handle.getLockKey().trim();
        String expectedOwner = handle.getLockOwner();

        AtomicBoolean released = new AtomicBoolean(false);
        lockStore.computeIfPresent(jobName, (key, existing) -> {
            if (expectedOwner.equals(existing.getLockOwner())) {
                released.set(true);
                log.debug("Node [{}] successfully released cluster lock for job [{}]", nodeId, jobName);
                return null;
            }
            log.warn("Cluster lock mismatch for job [{}]: expected owner [{}], actual owner [{}] (lease may have expired and been taken over)",
                    jobName, expectedOwner, existing.getLockOwner());
            return existing;
        });

        return released.get();
    }

    @Override
    public boolean unlock(JobLockHandle handle, Duration minHoldTime) {
        if (ClusterLockSupport.isInvalidHandle(handle)) {
            return false;
        }
        ClusterLockSupport.MinHoldWindow window = ClusterLockSupport.resolveShrinkWindow(handle, minHoldTime);
        if (window == null) {
            return unlock(handle);
        }

        String jobName = handle.getLockKey().trim();
        String expectedOwner = handle.getLockOwner();
        AtomicBoolean updated = new AtomicBoolean(false);

        lockStore.computeIfPresent(jobName, (key, existing) -> {
            if (!expectedOwner.equals(existing.getLockOwner()) || existing.isExpired()) {
                log.warn("Cluster lock min-hold shrink skipped for job [{}]: owner mismatch or already expired", jobName);
                return existing;
            }
            Instant targetExpire = (existing.getLeaseExpireAt() != null
                    && window.targetExpireAt().isAfter(existing.getLeaseExpireAt()))
                    ? existing.getLeaseExpireAt()
                    : window.targetExpireAt();
            updated.set(true);
            log.debug("Node [{}] non-blocking shrunk cluster lock for job [{}] until [{}]", nodeId, jobName, targetExpire);
            return new LockEntry(existing.getLockOwner(), targetExpire);
        });

        return updated.get();
    }

    @Override
    public boolean renew(JobLockHandle handle, Duration leaseTime) {
        if (handle == null || !handle.isAcquired() || handle.getLockKey() == null || handle.getLockOwner() == null
                || leaseTime == null || leaseTime.isZero() || leaseTime.isNegative()) {
            return false;
        }

        String jobName = handle.getLockKey().trim();
        String expectedOwner = handle.getLockOwner();
        Instant newExpireAt = Instant.now().plus(leaseTime);
        AtomicBoolean renewed = new AtomicBoolean(false);

        lockStore.computeIfPresent(jobName, (key, existing) -> {
            if (!existing.isExpired() && expectedOwner.equals(existing.getLockOwner())) {
                renewed.set(true);
                log.debug("Node [{}] renewed cluster lock for job [{}] until [{}]", nodeId, jobName, newExpireAt);
                return new LockEntry(existing.getLockOwner(), newExpireAt);
            }
            return existing;
        });

        return renewed.get();
    }

    /**
     * 判断指定任务的集群互斥锁当前是否被有效持有（未过期）
     *
     * @param jobName 任务名称
     * @return true 表示锁正处于锁定状态
     */
    @Override
    public boolean isLocked(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return false;
        }
        LockEntry entry = lockStore.get(jobName.trim());
        return entry != null && !entry.isExpired();
    }

    /**
     * 获取当前持有锁的属主签名（未锁定返回 null）
     *
     * @param jobName 任务名称
     * @return 属主签名
     */
    public String getLockOwner(String jobName) {
        if (jobName == null) {
            return null;
        }
        LockEntry entry = lockStore.get(jobName.trim());
        return (entry != null && !entry.isExpired()) ? entry.getLockOwner() : null;
    }

    /**
     * 清理所有锁（测试用）
     */
    public void clear() {
        lockStore.clear();
    }

    public String getNodeId() {
        return nodeId;
    }

    private static String resolveDefaultNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node-" + IdUtil.fastSimpleUUID().substring(0, 8);
        }
    }

    /**
     * 内部锁实体记录
     */
    public static class LockEntry {
        private final String lockOwner;
        private final Instant leaseExpireAt;

        public LockEntry(String lockOwner, Instant leaseExpireAt) {
            this.lockOwner = lockOwner;
            this.leaseExpireAt = leaseExpireAt;
        }

        public String getLockOwner() {
            return lockOwner;
        }

        public Instant getLeaseExpireAt() {
            return leaseExpireAt;
        }

        public boolean isExpired() {
            return leaseExpireAt != null && Instant.now().isAfter(leaseExpireAt);
        }
    }
}
