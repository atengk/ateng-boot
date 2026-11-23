package io.github.atengk.boot.admin.cluster;

import io.github.atengk.boot.admin.silence.SilenceRule;

import java.time.Duration;
import java.util.List;

/**
 * Spring Boot Admin 多副本无脑裂集群协调契约 SPI
 * <p>
 * 默认采用纯内存单机实现；开启集群 Redis 协调后，在发生任何 Redis 网络故障时遵循 Fail-Open 降级放行原则，
 * 确保告警、清理与静默核心链路不因外部协调介质故障而瘫痪。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface AdminClusterCoordinator {

    /**
     * 尝试抢占指定名称的互斥分布式锁
     *
     * @param lockKey 互斥锁逻辑键名
     * @param ownerId 竞争节点/执行者唯一标识
     * @param ttl     锁自动租期
     * @return 抢占成功或触发 Fail-Open 降级时返回 {@code true}
     */
    boolean tryAcquireLock(String lockKey, String ownerId, Duration ttl);

    /**
     * 仅在当前节点仍持有该互斥锁时安全释放
     *
     * @param lockKey 互斥锁逻辑键名
     * @param ownerId 竞争节点/执行者唯一标识
     */
    void releaseLock(String lockKey, String ownerId);

    /**
     * 在给定窗口内尝试标记一条告警事件已投递，用于多副本去重防刷屏
     *
     * @param dedupKey 告警幂等去重键
     * @param ttl      去重记忆窗口期
     * @return 首次抢占成功允许发送（或 Fail-Open 降级）返回 {@code true}，重复节点返回 {@code false}
     */
    boolean tryMarkAlertDispatched(String dedupKey, Duration ttl);

    /**
     * 保存或更新动态维护静默规则
     *
     * @param rule 静默规则实体
     */
    void saveSilenceRule(SilenceRule rule);

    /**
     * 按规则 ID 撤销并删除静态/动态静默规则
     *
     * @param ruleId 规则唯一标识
     */
    void deleteSilenceRule(String ruleId);

    /**
     * 列举当前全部未过期的有效维护静默规则（只读视图）
     *
     * @return 有效静默规则列表（永不返回 null）
     */
    List<SilenceRule> listSilenceRules();

    /**
     * 批量清理并返回本次清除的已过期维护静默规则数量
     *
     * @return 本次清理的过期规则数
     */
    default int pruneExpiredSilenceRules() {
        return 0;
    }
}
