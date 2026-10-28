package io.github.atengk.boot.notification.util;

import io.github.atengk.boot.core.util.DesensitizeUtil;

/**
 * 协同通知凭据与地址脱敏安全工具类
 * 基于 Core 的 DesensitizeUtil 针对 Webhook URL 中的敏感 Token、Key 参数及加签 Secret 实施 100% 审计脱敏
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class NotificationDesensitizeUtil {

    private static final String DEFAULT_MASK = "******";

    private NotificationDesensitizeUtil() {
    }

    /**
     * 对 Webhook 完整 URL 进行安全脱敏
     * 自动识别 Query 参数（如 ?access_token=...、?key=...）或路径中的 Token 并打码，保留协议与前缀
     *
     * @param url 原始 Webhook 地址
     * @return 脱敏后的安全 Webhook 地址
     */
    public static String maskWebhook(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int len = url.length();
        if (len <= 15) {
            return DEFAULT_MASK;
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

    /**
     * 对加签 Secret 进行安全脱敏
     * 保留首 3 位与末 3 位，中间字符统一打码
     *
     * @param secret 原始加签密钥
     * @return 脱敏后的密钥字符串
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return secret;
        }
        int len = secret.length();
        if (len <= 6) {
            return DEFAULT_MASK;
        }
        return DesensitizeUtil.mask(secret, 3, len - 3);
    }
}
