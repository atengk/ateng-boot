package io.github.atengk.boot.job.local.lock;

import java.time.Duration;
import java.time.Instant;

/**
 * 轻量集群互斥锁最小持有窗口计算辅助工具
 *
 * @author Ateng
 * @since 2026-11-19
 */
final class ClusterLockSupport {

    private ClusterLockSupport() {
    }

    static boolean isInvalidHandle(JobLockHandle handle) {
        return handle == null
                || !handle.isAcquired()
                || handle.getLockKey() == null
                || handle.getLockOwner() == null;
    }

    /**
     * 计算最小持有窗口收缩后的有效目标过期时刻与剩余毫秒数；无需收缩或已过期时返回 {@code null}
     *
     * @param handle      锁持有句柄
     * @param minHoldTime 最小持有时长
     * @return 有效收缩窗口信息，或 {@code null} 表示应直接物理释放锁
     */
    static MinHoldWindow resolveShrinkWindow(JobLockHandle handle, Duration minHoldTime) {
        if (minHoldTime == null || minHoldTime.isZero() || minHoldTime.isNegative()) {
            return null;
        }
        Instant now = Instant.now();
        Instant acquiredAt = handle.getAcquiredAt() != null ? handle.getAcquiredAt() : now;
        Instant minHoldExpireAt = acquiredAt.plus(minHoldTime);
        if (!now.isBefore(minHoldExpireAt)) {
            return null;
        }
        Instant targetExpireAt = minHoldExpireAt;
        if (handle.getLeaseExpireAt() != null && targetExpireAt.isAfter(handle.getLeaseExpireAt())) {
            targetExpireAt = handle.getLeaseExpireAt();
        }
        long remainingMs = Duration.between(now, targetExpireAt).toMillis();
        if (remainingMs <= 0) {
            return null;
        }
        return new MinHoldWindow(targetExpireAt, remainingMs);
    }

    record MinHoldWindow(Instant targetExpireAt, long remainingMillis) {
    }
}
