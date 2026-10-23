package io.github.atengk.boot.notification.provider;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import org.springframework.web.client.RestClient;

/**
 * 协同通知平台驱动通信引擎 SPI
 * 抽象 supports(Platform) 协议协商与 send(NotificationChannel, NotificationMessage, RestClient) 发信契约
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationProvider {

    /**
     * 判断当前驱动是否支持给定的协同平台
     *
     * @param platform 协同平台枚举
     * @return true 若当前驱动支持
     */
    boolean supports(Platform platform);

    /**
     * 执行底层网络推送
     *
     * @param channel    目标通知渠道配置
     * @param message    待推送的领域消息
     * @param restClient HTTP 客户端实例
     * @return 发送结果领域载荷
     */
    NotificationSendResult send(NotificationChannel channel, NotificationMessage message, RestClient restClient);
}
