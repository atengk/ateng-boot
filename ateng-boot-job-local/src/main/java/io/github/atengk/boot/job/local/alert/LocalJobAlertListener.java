package io.github.atengk.boot.job.local.alert;

import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import org.springframework.context.event.EventListener;

/**
 * 任务异常事件告警监听分发器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobAlertListener {

    private final JobAlertHandler alertHandler;

    public LocalJobAlertListener(JobAlertHandler alertHandler) {
        this.alertHandler = alertHandler;
    }

    /**
     * 监听任务执行失败事件
     *
     * @param event 失败事件
     */
    @EventListener
    public void onJobFailed(JobFailedEvent event) {
        if (alertHandler == null || event == null) {
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
        alertHandler.handleAlert(context);
    }

    /**
     * 监听任务执行硬超时熔断事件
     *
     * @param event 超时事件
     */
    @EventListener
    public void onJobTimeout(JobTimeoutEvent event) {
        if (alertHandler == null || event == null) {
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
        alertHandler.handleAlert(context);
    }

    private JobAlertContext buildAlertContext(String jobName,
                                              io.github.atengk.boot.job.local.enums.TriggerType triggerType,
                                              String traceId,
                                              String alertType,
                                              long durationMs,
                                              int retryCount,
                                              String errorMsg,
                                              Throwable throwable,
                                              java.time.Instant timestamp) {
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
