package io.github.atengk.boot.notification.channel;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationChannelProperties;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 通知渠道运行时配置模型
 * 封装渠道名称、协同平台类型、Webhook URL、加签密钥与专属超时及频控配额
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@Builder
@EqualsAndHashCode
public class NotificationChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道唯一标识名称（如 ops-alert、finance-notice）
     */
    private final String name;

    /**
     * 协同平台类型
     */
    private final Platform platform;

    /**
     * 完整的 Webhook 推送地址
     */
    private final String webhook;

    /**
     * 加签签名密钥（可选）
     */
    private final String secret;

    /**
     * 当前渠道专属网络连接超时（可选，未设置时继承全局配置）
     */
    private final Duration connectTimeout;

    /**
     * 当前渠道专属网络读取超时（可选，未设置时继承全局配置）
     */
    private final Duration readTimeout;

    /**
     * 当前渠道专属每分钟频控配额阈值（可选，条/分钟）
     */
    private final Integer permitsPerMinute;

    /**
     * 备用通知渠道名称列表（当主渠道遭遇网络超时、HTTP 429 频控或 HTTP 5xx 故障等瞬态外部异常时，按序尝试备用渠道重试）
     */
    private final List<String> backupChannels;

    public NotificationChannel(String name, Platform platform, String webhook, String secret,
                               Duration connectTimeout, Duration readTimeout, Integer permitsPerMinute) {
        this(name, platform, webhook, secret, connectTimeout, readTimeout, permitsPerMinute, Collections.emptyList());
    }

    public NotificationChannel(String name, Platform platform, String webhook, String secret,
                               Duration connectTimeout, Duration readTimeout, Integer permitsPerMinute,
                               List<String> backupChannels) {
        BusinessAssert.isTrue(name != null && !name.isBlank(), "渠道名称不能为空");
        BusinessAssert.notNull(platform, "协同平台类型不能为空");
        BusinessAssert.isTrue(webhook != null && !webhook.isBlank(), "Webhook 推送地址不能为空");

        this.name = name.trim();
        this.platform = platform;
        this.webhook = webhook.trim();
        this.secret = (secret != null && !secret.isBlank()) ? secret.trim() : null;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.permitsPerMinute = permitsPerMinute;
        this.backupChannels = (backupChannels != null && !backupChannels.isEmpty())
                ? List.copyOf(backupChannels)
                : Collections.emptyList();
    }

    public List<String> getBackupChannels() {
        return backupChannels != null ? backupChannels : Collections.emptyList();
    }

    /**
     * 从配置属性对象构建渠道运行时配置
     *
     * @param name       渠道名称
     * @param properties 渠道配置属性
     * @return 运行时渠道对象
     */
    public static NotificationChannel fromProperties(String name, NotificationChannelProperties properties) {
        BusinessAssert.notNull(properties, "渠道配置属性不能为空");
        return NotificationChannel.builder()
                .name(name)
                .platform(properties.getPlatform())
                .webhook(properties.getWebhook())
                .secret(properties.getSecret())
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .permitsPerMinute(properties.getPermitsPerMinute())
                .backupChannels(properties.getBackupChannels())
                .build();
    }

    @Override
    public String toString() {
        return "NotificationChannel{" +
                "name='" + name + '\'' +
                ", platform=" + platform +
                ", webhook='" + NotificationDesensitizeUtil.maskWebhook(webhook) + '\'' +
                ", secret='" + NotificationDesensitizeUtil.maskSecret(secret) + '\'' +
                ", connectTimeout=" + connectTimeout +
                ", readTimeout=" + readTimeout +
                ", permitsPerMinute=" + permitsPerMinute +
                ", backupChannels=" + backupChannels +
                '}';
    }
}
