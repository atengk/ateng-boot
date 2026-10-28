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
}
