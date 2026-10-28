package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.notification.model.Platform;

/**
 * 协同平台 Webhook 报文构建适配器工厂门面
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class NotificationPayloadBuilders {

    private NotificationPayloadBuilders() {
    }

    /**
     * 根据平台类型获取对应的报文构建适配器
     *
     * @param platform 协同平台枚举
     * @return 对应的 NotificationPayloadBuilder 适配器实例
     */
    public static NotificationPayloadBuilder get(Platform platform) {
        if (platform == null) {
            return GenericPayloadBuilder.INSTANCE;
        }
        return switch (platform) {
            case DINGTALK -> DingTalkPayloadBuilder.INSTANCE;
            case WECOM -> WeComPayloadBuilder.INSTANCE;
            case FEISHU -> FeishuPayloadBuilder.INSTANCE;
            case GENERIC -> GenericPayloadBuilder.INSTANCE;
        };
    }
}
