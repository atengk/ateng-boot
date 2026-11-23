package io.github.atengk.boot.ai.graph;

import java.time.Duration;

/**
 * 认知状态图人机协作分布式租约锁管理器 SPI
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface GraphLockManager {

    /**
     * 获取指定工作流会话的租约锁
     *
     * @param threadId 会话线程标识
     * @param ttl 锁过期租期
     * @param lockPrefix 锁键名前缀
     * @return 成功获取时返回高熵令牌（Token），冲突时抛出 AI_GRAPH_LOCK_CONFLICT 业务异常
     */
    String acquireLock(String threadId, Duration ttl, String lockPrefix);

    /**
     * 安全释放租约锁（仅当当前持有该令牌时才执行释放）
     *
     * @param threadId 会话线程标识
     * @param lockToken 高熵令牌
     * @param lockPrefix 锁键名前缀
     */
    void releaseLock(String threadId, String lockToken, String lockPrefix);
}
