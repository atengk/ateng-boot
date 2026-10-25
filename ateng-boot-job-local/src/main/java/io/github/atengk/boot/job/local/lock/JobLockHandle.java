package io.github.atengk.boot.job.local.lock;

import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 集群互斥锁持有句柄
 * <p>
 * 封装锁标识、持有者唯一签名与租约到期时间，用于防死锁租约判定与释放时的所有权比对。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@ToString
public class JobLockHandle {

    /**
     * 锁定的任务名称 / 锁资源标识
     */
    private final String lockKey;

    /**
     * 锁持有者的唯一节点与线程签名（格式：{@code nodeId@threadId:uuid}）
     */
    private final String lockOwner;

    /**
     * 锁租约绝对过期时间戳
     */
    private final Instant leaseExpireAt;

    /**
     * 是否成功抢占并持有该锁
     */
    private final boolean acquired;

    public JobLockHandle(String lockKey, String lockOwner, Instant leaseExpireAt, boolean acquired) {
        this.lockKey = lockKey;
        this.lockOwner = lockOwner;
        this.leaseExpireAt = leaseExpireAt;
        this.acquired = acquired;
    }

    /**
     * 构建加锁成功的锁句柄
     *
     * @param lockKey       锁标识
     * @param lockOwner     持有者签名
     * @param leaseExpireAt 租约到期时间
     * @return 成功句柄
     */
    public static JobLockHandle success(String lockKey, String lockOwner, Instant leaseExpireAt) {
        return new JobLockHandle(lockKey, lockOwner, leaseExpireAt, true);
    }

    /**
     * 构建加锁失败的锁句柄
     *
     * @param lockKey 锁标识
     * @return 失败句柄
     */
    public static JobLockHandle failed(String lockKey) {
        return new JobLockHandle(lockKey, null, null, false);
    }

    /**
     * 判断当前锁句柄是否已成功持有锁
     *
     * @return true 表示成功获取
     */
    public boolean isAcquired() {
        return acquired;
    }

    /**
     * 判断当前锁租约是否已超时过期
     *
     * @return true 表示租约已失效
     */
    public boolean isExpired() {
        return leaseExpireAt != null && Instant.now().isAfter(leaseExpireAt);
    }
}
