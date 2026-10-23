package io.github.atengk.boot.notification.handler;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认协同通知发信失败日志记录处理器
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class LoggingNotificationSendFailureHandler implements NotificationSendFailureHandler {

    @Override
    public void onFailure(NotificationChannel channel, NotificationMessage message, Throwable throwable) {
        String channelName = channel != null ? channel.getName() : "unknown";
        String platform = (channel != null && channel.getPlatform() != null) ? channel.getPlatform().name() : "unknown";
        log.error("协同通知异步/事务提交后发信执行失败 [channel={}, platform={}]: {}",
                channelName, platform, throwable != null ? throwable.getMessage() : "unknown", throwable);
    }
}
