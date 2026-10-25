package io.github.atengk.boot.job.local.event;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Getter;

import java.time.Duration;

/**
 * 任务执行硬超时熔断事件
 * <p>
 * 任务单次执行超过配置的 {@code timeout} 阈值被强行发送中断信号后发布。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class JobTimeoutEvent extends JobEvent {

    /**
     * 超时截断总耗时（毫秒）
     */
    private final long durationMs;

    /**
     * 配置的超时截断阈值
     */
    private final Duration timeout;

    /**
     * 重试次数
     */
    private final int retryCount;

    public JobTimeoutEvent(Object source, String jobName, TriggerType triggerType, String traceId,
                           long durationMs, Duration timeout, int retryCount) {
        super(source, jobName, triggerType, traceId);
        this.durationMs = durationMs;
        this.timeout = timeout;
        this.retryCount = retryCount;
    }
}
