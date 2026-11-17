package io.github.atengk.boot.notification.limiter;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationRateLimitProperties;

import java.time.Duration;

/**
 * 协同通知客户端频控器公共抽象基类
 * 统一收敛平台基准配额解析、单秒突发阈值推导、默认最大排队时长及入参渠道防卫校验，消除单机与分布式实现间的重复逻辑
 *
 * @author Ateng
 * @since 2026-11-17
 */
public abstract class AbstractNotificationRateLimiter implements NotificationRateLimiter {

    protected final NotificationRateLimitProperties properties;

    protected AbstractNotificationRateLimiter(NotificationRateLimitProperties properties) {
        this.properties = properties != null ? properties : new NotificationRateLimitProperties();
    }

    /**
     * 前置校验通知渠道及其唯一标识合法性，并返回标准化后的渠道名称
     *
     * @param channel 目标通知渠道
     * @return 去除首尾空白后的渠道名称
     */
    protected String validateAndNormalizeChannelName(NotificationChannel channel) {
        BusinessAssert.notNull(channel, "通知渠道不能为空");
        return validateChannelName(channel.getName());
    }

    /**
     * 校验并返回去除首尾空白后的渠道名称
     *
     * @param channelName 渠道标识名称
     * @return 标准化渠道名称
     */
    protected String validateChannelName(String channelName) {
        BusinessAssert.isTrue(channelName != null && !channelName.isBlank(), "渠道名称不能为空");
        return channelName.trim();
    }

    @Override
    public int resolvePermitsPerMinute(NotificationChannel channel) {
        if (channel == null) {
            return properties.getDefaultGenericPermits();
        }
        if (channel.getPermitsPerMinute() != null && channel.getPermitsPerMinute() > 0) {
            return channel.getPermitsPerMinute();
        }
        Platform platform = channel.getPlatform();
        if (platform == null) {
            return properties.getDefaultGenericPermits();
        }
        return switch (platform) {
            case DINGTALK -> properties.getDefaultDingTalkPermits();
            case WECOM -> properties.getDefaultWeComPermits();
            case FEISHU -> properties.getDefaultFeishuPermits();
            case GENERIC -> properties.getDefaultGenericPermits();
        };
    }

    @Override
    public int resolveBurstPermitsPerSecond(NotificationChannel channel) {
        if (channel == null || !properties.isEnabled()) {
            return 0;
        }
        if (channel.getBurstPermitsPerSecond() != null && channel.getBurstPermitsPerSecond() > 0) {
            return channel.getBurstPermitsPerSecond();
        }
        Platform platform = channel.getPlatform();
        if (platform == Platform.FEISHU) {
            return properties.getDefaultFeishuBurstPermits();
        }
        return 0;
    }

    @Override
    public Duration getDefaultMaxWaitDuration() {
        return properties.getMaxWaitDuration() != null ? properties.getMaxWaitDuration() : Duration.ofSeconds(2);
    }
}
