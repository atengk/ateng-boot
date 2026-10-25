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
        JobAlertContext context = JobAlertContext.builder()
                .jobName(event.getJobName())
                .triggerType(event.getTriggerType())
                .traceId(event.getTraceId())
                .alertType("FAILED")
                .durationMs(event.getDurationMs())
                .retryCount(event.getRetryCount())
                .errorMsg(event.getErrorMsg())
                .throwable(event.getThrowable())
                .timestamp(event.getEventTime())
                .build();
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
        JobAlertContext context = JobAlertContext.builder()
                .jobName(event.getJobName())
                .triggerType(event.getTriggerType())
                .traceId(event.getTraceId())
                .alertType("TIMEOUT")
                .durationMs(event.getDurationMs())
                .retryCount(event.getRetryCount())
                .errorMsg("Job execution timed out after " + event.getTimeout())
                .timestamp(event.getEventTime())
                .build();
        alertHandler.handleAlert(context);
    }
}
