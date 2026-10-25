package io.github.atengk.boot.job.local.event;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Getter;

/**
 * 任务执行成功事件
 * <p>
 * 任务单次调度全流程正常执行完毕并获得执行产物后发布。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class JobSuccessEvent extends JobEvent {

    /**
     * 执行总耗时（毫秒）
     */
    private final long durationMs;

    /**
     * 处理器返回值 / 执行成果
     */
    private final Object result;

    public JobSuccessEvent(Object source, String jobName, TriggerType triggerType, String traceId, long durationMs, Object result) {
        super(source, jobName, triggerType, traceId);
        this.durationMs = durationMs;
        this.result = result;
    }
}
