package io.github.atengk.boot.job.local.event;

import io.github.atengk.boot.job.local.enums.DiscardReason;
import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Getter;

/**
 * 任务跳过/丢弃事件
 * <p>
 * 任务因重叠防卫策略（{@code DISCARD}）或轻量集群互斥锁已被其他节点抢占而安全跳过时发布。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class JobDiscardedEvent extends JobEvent {

    /**
     * 强类型丢弃原因枚举
     */
    private final DiscardReason discardReason;

    /**
     * 跳过/丢弃原因说明文本
     */
    private final String reason;

    public JobDiscardedEvent(Object source, String jobName, TriggerType triggerType, String traceId, String reason) {
        this(source, jobName, triggerType, traceId, resolveDefaultReason(reason), reason);
    }

    public JobDiscardedEvent(Object source, String jobName, TriggerType triggerType, String traceId,
                             DiscardReason discardReason, String reason) {
        super(source, jobName, triggerType, traceId);
        this.discardReason = discardReason != null ? discardReason : DiscardReason.OTHER;
        this.reason = reason;
    }

    private static DiscardReason resolveDefaultReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DiscardReason.OTHER;
        }
        String lower = reason.toLowerCase();
        if (lower.contains("cluster lock")) {
            return DiscardReason.CLUSTER_LOCKED;
        }
        if (lower.contains("discard")) {
            return DiscardReason.OVERLAPPING_DISCARD;
        }
        return DiscardReason.OTHER;
    }
}
