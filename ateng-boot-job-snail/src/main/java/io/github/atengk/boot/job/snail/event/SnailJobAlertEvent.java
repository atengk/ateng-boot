package io.github.atengk.boot.job.snail.event;

import org.springframework.context.ApplicationEvent;

import java.io.Serializable;
import java.time.Duration;

/**
 * SnailJob 分布式任务调度告警与自愈事件
 *
 * <p>包含首发失败、持续失败聚合摘要以及故障自愈恢复三种核心事件形态，
 * 携带任务标识、参数载荷、全链路追踪 MDC traceId、持续耗时、累计失败计数与异常信息。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobAlertEvent extends ApplicationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 告警事件类型
     */
    public enum AlertType {
        /**
         * 首次失败告警 (第一阶段：立即触发卡片推送)
         */
        INITIAL_FAILURE,

        /**
         * 持续失败聚合摘要 (第二阶段：滑动窗口抑制周期或超频阈值汇总推送)
         */
        FAILURE_SUMMARY,

        /**
         * 故障自愈恢复通知 (第三阶段：连续失败后首次成功执行推送恢复卡片)
         */
        RECOVERY
    }

    private final AlertType alertType;
    private final String jobName;
    private final String param;
    private final String traceId;
    private final long durationMs;
    private final int failureCount;
    private final Duration failureDuration;
    private final Throwable throwable;

    public SnailJobAlertEvent(
            Object source,
            AlertType alertType,
            String jobName,
            String param,
            String traceId,
            long durationMs,
            int failureCount,
            Duration failureDuration,
            Throwable throwable) {
        super(source);
        this.alertType = alertType;
        this.jobName = jobName;
        this.param = param;
        this.traceId = traceId;
        this.durationMs = durationMs;
        this.failureCount = failureCount;
        this.failureDuration = failureDuration;
        this.throwable = throwable;
    }

    public AlertType getAlertType() {
        return alertType;
    }

    public String getJobName() {
        return jobName;
    }

    public String getParam() {
        return param;
    }

    public String getTraceId() {
        return traceId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Duration getFailureDuration() {
        return failureDuration;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public String toString() {
        return "SnailJobAlertEvent{" +
                "alertType=" + alertType +
                ", jobName='" + jobName + '\'' +
                ", param='" + param + '\'' +
                ", traceId='" + traceId + '\'' +
                ", durationMs=" + durationMs +
                ", failureCount=" + failureCount +
                ", failureDuration=" + failureDuration +
                ", throwable=" + (throwable != null ? throwable.getMessage() : "null") +
                '}';
    }
}
