package io.github.atengk.boot.job.local.alert;

/**
 * 任务告警分发处理 SPI
 * <p>
 * 支持将任务失败/超时异常告警分发至控制台日志、企业微信/钉钉/飞书群机器人或第三方通知渠道。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface JobAlertHandler {

    /**
     * 处理任务告警推送
     *
     * @param context 告警上下文信息
     */
    void handleAlert(JobAlertContext context);
}
