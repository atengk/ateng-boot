package io.github.atengk.boot.job.local.lock;

import java.time.Duration;

/**
 * 轻量集群互斥锁 SPI
 * <p>
 * 面向无中心调度架构（如云原生 K8s 多副本或多节点部署），通过外置锁存储（Redis / JDBC / 内存）
 * 协调集群中各节点的同名任务互斥，避免多实例并发重复调度。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface JobClusterLockProvider {

    /**
     * 尝试获取集群互斥锁
     *
     * @param jobName   任务唯一标识名称
     * @param leaseTime 锁安全防死锁租约时长（租约到期后若持有节点宕机则自动失效）
     * @return 锁句柄（若抢锁失败则 {@link JobLockHandle#isAcquired()} 为 false）
     */
    JobLockHandle tryLock(String jobName, Duration leaseTime);

    /**
     * 防御性释放集群互斥锁
     * <p>
     * 必须且只能校验 {@code handle} 中的 {@code lockOwner} 持有者签名，严禁误释放他人已抢占的锁。
     * </p>
     *
     * @param handle 锁持有句柄
     * @return 是否释放成功（若锁已过期转移或属主不匹配返回 false）
     */
    boolean unlock(JobLockHandle handle);

    /**
     * 带最小持有时间约束的释放集群互斥锁（零线程阻塞防跨节点时钟漂移）
     * <p>
     * 当任务实际持有耗时小于 {@code minHoldTime} 时，不物理删锁，而是非阻塞地将锁剩余有效期收缩为
     * {@code minHoldTime - elapsed}，在剩余窗口期内阻挡存在 NTP 时钟偏移的慢节点重复抢锁。
     * </p>
     *
     * @param handle      锁持有句柄
     * @param minHoldTime 最小锁持有时长（为 null 或非正数时直接物理删除释放）
     * @return 是否释放或收缩租期成功
     */
    default boolean unlock(JobLockHandle handle, Duration minHoldTime) {
        return unlock(handle);
    }

    /**
     * 对已持有的集群互斥锁执行租约续期
     *
     * @param handle    锁持有句柄
     * @param leaseTime 新的续期租约时长（必须为正数）
     * @return 是否续期成功（属主不匹配、锁已过期或入参非法时返回 false）
     */
    default boolean renew(JobLockHandle handle, Duration leaseTime) {
        return false;
    }

    /**
     * 判断指定任务的集群互斥锁当前是否处于有效锁定状态
     *
     * @param jobName 任务唯一标识名称
     * @return true 表示锁当前正被持有且未过期
     */
    default boolean isLocked(String jobName) {
        return false;
    }
}
