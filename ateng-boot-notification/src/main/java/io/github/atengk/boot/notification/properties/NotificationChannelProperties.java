package io.github.atengk.boot.notification.properties;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.notification.model.Platform;
import lombok.Data;

import java.time.Duration;

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
     * 加签签名密钥（钉钉 / 飞书必填加签参数）
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

    @Override
    public String toString() {
        return "NotificationChannelProperties{" +
                "platform=" + platform +
                ", webhook='" + maskWebhook(webhook) + '\'' +
                ", secret='" + maskSecret(secret) + '\'' +
                ", connectTimeout=" + connectTimeout +
                ", readTimeout=" + readTimeout +
                ", permitsPerMinute=" + permitsPerMinute +
                '}';
    }

    private static String maskWebhook(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int len = url.length();
        if (len <= 15) {
            return "******";
        }
        int queryIdx = url.indexOf('?');
        if (queryIdx > 0 && queryIdx + 5 < len) {
            return DesensitizeUtil.mask(url, queryIdx + 1, Math.max(queryIdx + 1, len - 4));
        }
        int hookIdx = url.indexOf("/hook/");
        if (hookIdx > 0 && hookIdx + 6 < len) {
            return DesensitizeUtil.mask(url, hookIdx + 6, Math.max(hookIdx + 6, len - 4));
        }
        return DesensitizeUtil.mask(url, 15, len - 4);
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return secret;
        }
        int len = secret.length();
        if (len <= 6) {
            return "******";
        }
        return DesensitizeUtil.mask(secret, 3, len - 3);
    }
}
