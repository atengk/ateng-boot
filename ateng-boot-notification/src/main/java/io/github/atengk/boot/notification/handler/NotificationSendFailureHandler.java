package io.github.atengk.boot.notification.handler;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;

/**
 * 协同通知发信失败补偿处理器 SPI
 * 负责在本地事务感知发信（sendAfterCommit）与受管异步发信失败时执行降级落库、告警或重试补偿
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationSendFailureHandler {

    /**
     * 发信失败回调补偿入口
     *
     * @param channel   目标通知渠道
     * @param message   待发送的消息
     * @param throwable 失败原因与异常链路
     */
    void onFailure(NotificationChannel channel, NotificationMessage message, Throwable throwable);
}
