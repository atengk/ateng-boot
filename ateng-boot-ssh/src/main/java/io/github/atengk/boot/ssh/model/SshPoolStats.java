package io.github.atengk.boot.ssh.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * SSH 受管会话缓存池运行态统计快照
 * <p>
 * 以不可变值对象封装会话池最大容量、当前缓存条目数、活跃健康会话数及累计新建、命中、淘汰与建连失败计数，
 * 为运维诊断看板与健康监控提供零副作用只读观测视图。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder
public final class SshPoolStats {

    /**
     * 会话池最大活跃容量上限
     */
    private final int maxSessions;

    /**
     * 当前缓存条目总数
     */
    private final int poolSize;

    /**
     * 当前处于活跃可用状态的会话数
     */
    private final int activeSessions;

    /**
     * 累计新建池化会话次数
     */
    private final long totalCreated;

    /**
     * 累计缓存复用命中次数
     */
    private final long totalHits;

    /**
     * 累计淘汰及主动驱逐会话次数
     */
    private final long totalEvicted;

    /**
     * 累计建连或认证失败次数
     */
    private final long totalFailed;

    /**
     * 计算会话池缓存复用命中率（取值范围 {@code [0.0, 1.0]}）
     *
     * @return 命中率；若尚无池化请求则返回 {@code 0.0}
     */
    public double getHitRate() {
        long totalRequests = totalHits + totalCreated;
        if (totalRequests <= 0L) {
            return 0.0d;
        }
        return (double) totalHits / totalRequests;
    }
}
