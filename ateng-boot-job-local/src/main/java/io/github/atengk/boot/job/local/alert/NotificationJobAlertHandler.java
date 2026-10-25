package io.github.atengk.boot.job.local.alert;

import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 ateng-boot-notification 的群机器人（钉钉/企微/飞书）告警转发处理器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class NotificationJobAlertHandler implements JobAlertHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobAlertHandler.class);

    private final NotificationTemplate notificationTemplate;
    private final String channel;

    public NotificationJobAlertHandler(NotificationTemplate notificationTemplate, String channel) {
        this.notificationTemplate = notificationTemplate;
        this.channel = channel;
    }

    @Override
    public void handleAlert(JobAlertContext context) {
        if (context == null || notificationTemplate == null) {
            return;
        }

        String title = "⚠️ [定时任务告警] " + context.getJobName() + " " + context.getAlertType();
        StringBuilder content = new StringBuilder();
        content.append("### 定时任务执行异常告警\n\n");
        content.append("- **任务名称**：`").append(context.getJobName()).append("`\n");
        content.append("- **告警类型**：`").append(context.getAlertType()).append("`\n");
        content.append("- **触发方式**：`").append(context.getTriggerType()).append("`\n");
        content.append("- **链路追踪**：`").append(context.getTraceId()).append("`\n");
        content.append("- **执行耗时**：").append(context.getDurationMs()).append(" ms\n");
        content.append("- **重试次数**：").append(context.getRetryCount()).append("\n");
        content.append("- **错误摘要**：`").append(context.getErrorMsg() != null ? context.getErrorMsg() : "N/A").append("`\n");
        content.append("- **告警时间**：").append(context.getTimestamp()).append("\n");

        MarkdownMessage message = MarkdownMessage.of(title, content.toString());

        try {
            if (channel != null && !channel.isBlank()) {
                notificationTemplate.sendAsync(channel.trim(), message);
            } else {
                notificationTemplate.sendAsync(message);
            }
            log.info("Dispatched job alert notification for [{}] via NotificationTemplate", context.getJobName());
        } catch (Throwable ex) {
            log.warn("Failed to dispatch job alert notification for [{}]: {}", context.getJobName(), ex.getMessage());
        }
    }
}
