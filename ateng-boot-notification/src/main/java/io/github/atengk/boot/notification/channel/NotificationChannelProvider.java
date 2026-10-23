package io.github.atengk.boot.notification.channel;

/**
 * 通知渠道动态供给器 SPI
 * 支持应用根据渠道名称动态从外部介质（如数据库、配置中心等）按需懒加载 NotificationChannel
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationChannelProvider {

    /**
     * 根据渠道名称动态加载通知渠道配置
     *
     * @param channelName 目标渠道名称
     * @return 匹配的渠道运行时配置，若不存在则返回 null
     */
    NotificationChannel loadChannel(String channelName);
}
