package io.github.atengk.boot.notification.event;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 协同通知渠道驱逐注销事件
 * 当运行时动态注销或热更新渠道配置时触发，供下游缓存清理、连接池回收与审计监听使用
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationChannelEvictEvent extends ApplicationEvent {

    private final String channelName;
    private final NotificationChannel evictedChannel;

    public NotificationChannelEvictEvent(Object source, String channelName, NotificationChannel evictedChannel) {
        super(source);
        this.channelName = channelName;
        this.evictedChannel = evictedChannel;
    }
}
