package io.github.atengk.boot.notification.event;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import lombok.Getter;

/**
 * 协同通知出站发送成功事件
 * 在协同平台网络响应成功且解析出发送结果载荷后触发
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationSentEvent extends NotificationSendEvent {

    private final NotificationSendResult result;

    public NotificationSentEvent(Object source, NotificationChannel channel, NotificationMessage message, NotificationSendResult result) {
        super(source, channel, message);
        this.result = result;
    }
}
