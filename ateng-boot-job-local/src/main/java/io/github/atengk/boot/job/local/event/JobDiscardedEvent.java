package io.github.atengk.boot.job.local.event;

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
     * 跳过/丢弃原因说明
     */
    private final String reason;

    public JobDiscardedEvent(Object source, String jobName, TriggerType triggerType, String traceId, String reason) {
        super(source, jobName, triggerType, traceId);
        this.reason = reason;
    }
}
