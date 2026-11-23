package io.github.atengk.boot.notification.util;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.util.Base64;
import java.util.Locale;

/**
 * 协同通知模块统一参数与防爆校验内部工具类
 * 收敛 HTTP(S) URL 合法性校验、正数容量阈值解析及 Base64 防 OOM 预检解码，避免各组件重复校验逻辑
 *
 * @author Ateng
 * @since 2026-11-17
 */
public final class NotificationValidationUtil {

    private NotificationValidationUtil() {
    }

    /**
     * 校验目标字符串是否为包含合法主机名的 HTTP(S) URL
     *
     * @param url   待校验 URL 字符串
     * @param label 业务字段中文名称（用于异常提示）
     * @return 去除首尾空白后的标准 URL
     */
    public static String validateHttpUrl(String url, String label) {
        if (url == null || url.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    label + "不能为空");
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    label + "协议非法，仅支持 http:// 或 https://");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    label + "格式非法: " + ex.getMessage(), ex);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    label + "缺少合法主机名");
        }
        return trimmed;
    }

    /**
     * 解析容量限制字节数（未配置时使用默认值，显式传入 0 或负数时快速失败）
     *
     * @param configuredMax 外部传入或配置的容量限制
     * @param defaultMax    默认兜底容量限制
     * @param configDesc    配置项或参数描述（用于异常提示）
     * @return 校验通过的正数最大字节数
     */
    public static long resolvePositiveMaxBytes(DataSize configuredMax, DataSize defaultMax, String configDesc) {
        if (configuredMax == null) {
            return defaultMax.toBytes();
        }
        long bytes = configuredMax.toBytes();
        if (bytes <= 0L) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    configDesc + " 必须大于 0 bytes，当前值: " + bytes);
        }
        return bytes;
    }

    /**
     * 安全解码 Base64 字符串并在失败时统一转译为受控业务异常
     *
     * @param base64 Base64 编码字符串
     * @return 解码后的原始字节数组
     */
    public static byte[] decodeBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    "图片 Base64 编码内容不能为空");
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    "图片 Base64 编码格式非法: " + ex.getMessage(), ex);
        }
    }

    /**
     * 在解码分配堆内存前先基于 Base64 编码长度上界进行防 OOM 拦截，再校验精确解码字节数
     *
     * @param base64   Base64 编码字符串
     * @param maxBytes 允许的最大解码字节数（必须大于 0）
     * @return 校验通过的解码后字节数组
     */
    public static byte[] decodeBase64WithinLimit(String base64, long maxBytes) {
        if (base64 == null || base64.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    "图片 Base64 编码内容不能为空");
        }
        String trimmed = base64.trim();
        long maxEncodedLength = maxBytes >= (Long.MAX_VALUE / 4L) * 3L
                ? Long.MAX_VALUE
                : (maxBytes / 3L) * 4L + (maxBytes % 3L == 0L ? 0L : 4L);
        if (trimmed.length() > maxEncodedLength) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    "图片大小超出配置上限: Base64 编码长度 " + trimmed.length() + " > 最大允许长度 " + maxEncodedLength + " (对应 " + maxBytes + " bytes)");
        }
        byte[] decoded = decodeBase64(trimmed);
        if (decoded.length > maxBytes) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    "图片大小超出配置上限: " + decoded.length + " bytes > " + maxBytes + " bytes");
        }
        return decoded;
    }
}
