package io.github.atengk.boot.sample.ssh.vo;

import io.github.atengk.boot.ssh.model.SshPoolStats;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SSH 受管会话缓存池运行态统计快照 VO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshPoolStatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话池最大活跃容量上限
     */
    private Integer maxSessions;

    /**
     * 当前缓存条目总数
     */
    private Integer poolSize;

    /**
     * 当前处于活跃可用状态的会话数
     */
    private Integer activeSessions;

    /**
     * 累计新建池化会话次数
     */
    private Long totalCreated;

    /**
     * 累计缓存复用命中次数
     */
    private Long totalHits;

    /**
     * 累计淘汰及主动驱逐会话次数
     */
    private Long totalEvicted;

    /**
     * 累计建连或认证失败次数
     */
    private Long totalFailed;

    /**
     * 会话池缓存复用命中率（0.0 ~ 1.0）
     */
    private Double hitRate;

    /**
     * 从底层统计快照转换构建业务 VO
     *
     * @param stats 会话池统计快照
     * @return 业务 VO
     */
    public static SshPoolStatsVO from(SshPoolStats stats) {
        if (stats == null) {
            return null;
        }
        return SshPoolStatsVO.builder()
                .maxSessions(stats.getMaxSessions())
                .poolSize(stats.getPoolSize())
                .activeSessions(stats.getActiveSessions())
                .totalCreated(stats.getTotalCreated())
                .totalHits(stats.getTotalHits())
                .totalEvicted(stats.getTotalEvicted())
                .totalFailed(stats.getTotalFailed())
                .hitRate(stats.getHitRate())
                .build();
    }
}
