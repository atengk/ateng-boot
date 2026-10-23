package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.notification.model.Platform;

/**
 * 协同平台 Webhook 报文构建适配器工厂门面
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class NotificationPayloadBuilders {

    private static final DingTalkPayloadBuilder DINGTALK = new DingTalkPayloadBuilder();
    private static final WeComPayloadBuilder WECOM = new WeComPayloadBuilder();
    private static final FeishuPayloadBuilder FEISHU = new FeishuPayloadBuilder();
    private static final GenericPayloadBuilder GENERIC = new GenericPayloadBuilder();

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
            return GENERIC;
        }
        return switch (platform) {
            case DINGTALK -> DINGTALK;
            case WECOM -> WECOM;
            case FEISHU -> FEISHU;
            case GENERIC -> GENERIC;
        };
    }
}
