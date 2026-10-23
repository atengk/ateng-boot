package io.github.atengk.boot.notification.event;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import lombok.Getter;

import java.time.Duration;

/**
 * 协同通知出站发送失败事件
 * 当频控超限、网络超时或平台返回拒绝响应时触发
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationSendFailedEvent extends NotificationSendEvent {

    private final Throwable throwable;
    private final Duration costDuration;

    public NotificationSendFailedEvent(Object source, NotificationChannel channel, NotificationMessage message,
                                       Throwable throwable, Duration costDuration) {
        super(source, channel, message);
        this.throwable = throwable;
        this.costDuration = costDuration;
    }
}
