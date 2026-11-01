package io.github.atengk.boot.job.local.enums;

import lombok.Getter;

/**
 * 定时任务丢弃/跳过调度原因枚举
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Getter
public enum DiscardReason {

    /**
     * 重叠并发调度防护丢弃（触发 DISCARD 防雪崩策略）
     */
    OVERLAPPING_DISCARD("重叠调度防雪崩跳过"),

    /**
     * 轻量集群互斥锁被其他节点持有
     */
    CLUSTER_LOCKED("集群互斥锁被其他节点持有"),

    /**
     * 其他自定义或未知原因
     */
    OTHER("其他原因");

    private final String description;

    DiscardReason(String description) {
        this.description = description;
    }
}
