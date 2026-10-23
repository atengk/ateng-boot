package io.github.atengk.boot.notification.event;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import lombok.Getter;

/**
 * 协同通知出站前发送中事件
 * 在频控校验通过、网络请求发送前触发
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationSendingEvent extends NotificationSendEvent {

    public NotificationSendingEvent(Object source, NotificationChannel channel, NotificationMessage message) {
        super(source, channel, message);
    }
}
