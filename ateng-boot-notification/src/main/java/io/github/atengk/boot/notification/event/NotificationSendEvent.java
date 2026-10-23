package io.github.atengk.boot.notification.event;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 协同通知生命周期事件抽象基类
 * 结构化承载目标渠道与发送消息领域实体，供下游审计、监控与事件溯源监听使用
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public abstract class NotificationSendEvent extends ApplicationEvent {

    private final NotificationChannel channel;
    private final NotificationMessage message;

    protected NotificationSendEvent(Object source, NotificationChannel channel, NotificationMessage message) {
        super(source);
        this.channel = channel;
        this.message = message;
    }
}
