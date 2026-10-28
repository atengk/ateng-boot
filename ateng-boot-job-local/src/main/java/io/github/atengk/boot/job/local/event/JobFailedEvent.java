package io.github.atengk.boot.job.local.event;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Getter;

/**
 * 任务执行失败事件
 * <p>
 * 任务执行抛出异常且重试耗尽后发布，携带最终捕获异常与重试统计。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class JobFailedEvent extends JobEvent {

    /**
     * 执行总耗时（毫秒）
     */
    private final long durationMs;

    /**
     * 实际退避重试次数
     */
    private final int retryCount;

    /**
     * 失败根因异常
     */
    private final Throwable throwable;

    /**
     * 错误摘要信息
     */
    private final String errorMsg;

    public JobFailedEvent(Object source, String jobName, TriggerType triggerType, String traceId,
                          long durationMs, int retryCount, Throwable throwable, String errorMsg) {
        super(source, jobName, triggerType, traceId);
        this.durationMs = durationMs;
        this.retryCount = retryCount;
        this.throwable = throwable;
        this.errorMsg = errorMsg;
    }
}
