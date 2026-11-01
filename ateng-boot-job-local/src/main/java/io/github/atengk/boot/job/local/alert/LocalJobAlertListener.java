package io.github.atengk.boot.job.local.alert;

import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import org.springframework.context.event.EventListener;

import java.time.Instant;

/**
 * 任务异常事件与自愈恢复告警监听分发器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobAlertListener {

    private final JobAlertHandler alertHandler;
    private final AntiStormJobAlertAggregator antiStormAggregator;

    public LocalJobAlertListener(JobAlertHandler alertHandler) {
        this(alertHandler, null);
    }

    public LocalJobAlertListener(JobAlertHandler alertHandler, AntiStormJobAlertAggregator antiStormAggregator) {
        this.alertHandler = alertHandler;
        this.antiStormAggregator = antiStormAggregator;
    }

    /**
     * 监听任务执行失败事件
     *
     * @param event 失败事件
     */
    @EventListener
    public void onJobFailed(JobFailedEvent event) {
        if (event == null) {
            return;
        }
        JobAlertContext context = buildAlertContext(
                event.getJobName(),
                event.getTriggerType(),
                event.getTraceId(),
                JobAlertContext.ALERT_TYPE_FAILED,
                event.getDurationMs(),
                event.getRetryCount(),
                event.getErrorMsg(),
                event.getThrowable(),
                event.getEventTime()
        );
        dispatchAlert(context);
    }

    /**
     * 监听任务执行硬超时熔断事件
     *
     * @param event 超时事件
     */
    @EventListener
    public void onJobTimeout(JobTimeoutEvent event) {
        if (event == null) {
            return;
        }
        JobAlertContext context = buildAlertContext(
                event.getJobName(),
                event.getTriggerType(),
                event.getTraceId(),
                JobAlertContext.ALERT_TYPE_TIMEOUT,
                event.getDurationMs(),
                event.getRetryCount(),
                "Job execution timed out after " + event.getTimeout(),
                null,
                event.getEventTime()
        );
        dispatchAlert(context);
    }

    /**
     * 监听任务执行成功事件（检测并触发故障自愈闭环通知）
     *
     * @param event 成功事件
     */
    @EventListener
    public void onJobSuccess(JobSuccessEvent event) {
        if (event == null) {
            return;
        }
        if (antiStormAggregator != null) {
            antiStormAggregator.handleSuccess(
                    event.getJobName(),
                    event.getTriggerType(),
                    event.getTraceId(),
                    event.getDurationMs()
            );
        }
    }

    private void dispatchAlert(JobAlertContext context) {
        if (antiStormAggregator != null) {
            antiStormAggregator.handleAlert(context);
        } else if (alertHandler != null) {
            alertHandler.handleAlert(context);
        }
    }

    private JobAlertContext buildAlertContext(String jobName,
                                              TriggerType triggerType,
                                              String traceId,
                                              String alertType,
                                              long durationMs,
                                              int retryCount,
                                              String errorMsg,
                                              Throwable throwable,
                                              Instant timestamp) {
        return JobAlertContext.builder()
                .jobName(jobName)
                .triggerType(triggerType)
                .traceId(traceId)
                .alertType(alertType)
                .durationMs(durationMs)
                .retryCount(retryCount)
                .errorMsg(errorMsg)
                .throwable(throwable)
                .timestamp(timestamp)
                .build();
    }
}
