package io.github.atengk.boot.job.local.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于受管日志框架的告警兜底处理器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LoggingJobAlertHandler implements JobAlertHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingJobAlertHandler.class);

    @Override
    public void handleAlert(JobAlertContext context) {
        if (context == null) {
            return;
        }
        log.error("[JOB_ALERT] [{}] Local Job [{}] {}! Trigger: [{}], TraceId: [{}], Duration: [{}ms], Retries: [{}], Error: [{}]",
                context.getAlertType(),
                context.getJobName(),
                "TIMEOUT".equals(context.getAlertType()) ? "TIMED OUT" : "FAILED",
                context.getTriggerType(),
                context.getTraceId(),
                context.getDurationMs(),
                context.getRetryCount(),
                context.getErrorMsg(),
                context.getThrowable());
    }
}
