package io.github.atengk.boot.notification.properties;

import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 协同通知单个渠道配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class NotificationChannelProperties {

    /**
     * 协同平台类型（DINGTALK | WECOM | FEISHU | GENERIC）
     */
    private Platform platform;

    /**
     * Webhook 完整推送地址
     */
    private String webhook;

    /**
     * 加签签名密钥（钉钉 / 飞书加签参数；企业微信官方 Webhook 机器人仅依赖 URL 中的 key 鉴权，无 secret 机制）
     */
    private String secret;

    /**
     * 当前渠道专属网络连接超时（缺省回退全局 connectTimeout）
     */
    private Duration connectTimeout;

    /**
     * 当前渠道专属网络读取超时（缺省回退全局 readTimeout）
     */
    private Duration readTimeout;

    /**
     * 当前渠道专属每分钟频控配额阈值（条/分钟），未设置时依据平台基线默认配置
     */
    private Integer permitsPerMinute;

    /**
     * 备用通知渠道名称列表（当主渠道遭遇网络超时、HTTP 429 频控或 HTTP 5xx 故障等瞬态外部异常时，按序尝试备用渠道重试）
     */
    private List<String> backupChannels = new ArrayList<>();

    @Override
    public String toString() {
        return "NotificationChannelProperties{" +
                "platform=" + platform +
                ", webhook='" + NotificationDesensitizeUtil.maskWebhook(webhook) + '\'' +
                ", secret='" + NotificationDesensitizeUtil.maskSecret(secret) + '\'' +
                ", connectTimeout=" + connectTimeout +
                ", readTimeout=" + readTimeout +
                ", permitsPerMinute=" + permitsPerMinute +
                ", backupChannels=" + backupChannels +
                '}';
    }
}
