package io.github.atengk.boot.notification.util;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 协同通知出站加签安全算法工具类
 * 提供钉钉、飞书及通用 Webhook 的 HMAC-SHA256 签名计算能力
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class NotificationSignUtil {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    private NotificationSignUtil() {
    }

    /**
     * 计算钉钉 Webhook URL 查询参数签名
     * 规则：HmacSHA256(timestamp + "\n" + secret)，使用 secret 作为密钥，Base64 编码后再进行 UTF-8 URLEncode
     *
     * @param secret          加签密钥
     * @param timestampMillis 13 位毫秒级时间戳
     * @return URLEncoder 编码后的签名字符串
     */
    public static String signDingTalk(String secret, long timestampMillis) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            String stringToSign = timestampMillis + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String base64Sign = Base64.getEncoder().encodeToString(signData);
            return URLEncoder.encode(base64Sign, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception ex) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR,
                    "计算钉钉加签签名失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 计算飞书 Webhook 请求体签名
     * 规则：HmacSHA256(timestamp + "\n" + secret) 计算摘要，以 stringToSign 作为密钥计算空字节哈希，并进行 Base64 编码
     *
     * @param secret           加签密钥
     * @param timestampSeconds 10 位秒级时间戳
     * @return Base64 编码的签名字符串
     */
    public static String signFeishu(String secret, long timestampSeconds) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            String stringToSign = timestampSeconds + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM));
            byte[] signData = mac.doFinal(new byte[0]);
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception ex) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR,
                    "计算飞书加签签名失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 计算通用 HMAC-SHA256 十六进制摘要签名
     *
     * @param secret  加签密钥
     * @param content 待签名报文内容
     * @return 小写 64 位十六进制签名字符串
     */
    public static String signHmacSha256Hex(String secret, String content) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM));
            byte[] signData = mac.doFinal((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signData);
        } catch (Exception ex) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR,
                    "计算 HMAC-SHA256 十六进制签名失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 计算通用 Webhook 防重放 HMAC-SHA256 十六进制签名
     * 规则：对 {@code timestampMillis + "\n" + payload} 计算 HMAC-SHA256 摘要
     *
     * @param secret          加签密钥
     * @param timestampMillis 13 位毫秒级时间戳
     * @param payload         JSON 报文内容
     * @return 小写 64 位十六进制签名字符串；密钥为空白时返回 null
     */
    public static String signGenericWebhook(String secret, long timestampMillis, String payload) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String contentToSign = timestampMillis + "\n" + (payload != null ? payload : "");
        return signHmacSha256Hex(secret, contentToSign);
    }

    /**
     * 使用常量时间比对校验通用 Webhook 防重放签名（防御时序侧信道攻击）
     *
     * @param secret            加签密钥
     * @param timestampMillis   请求头携带的 13 位毫秒级时间戳
     * @param payload           原始请求体 JSON 载荷
     * @param expectedSignature 请求头携带的十六进制签名（大小写不敏感）
     * @return true 若签名严格匹配
     */
    public static boolean verifyGenericWebhook(String secret, long timestampMillis, String payload, String expectedSignature) {
        if (secret == null || secret.isBlank() || expectedSignature == null || expectedSignature.isBlank()) {
            return false;
        }
        String computed = signGenericWebhook(secret, timestampMillis, payload);
        return constantTimeHexEquals(computed, expectedSignature);
    }

    /**
     * 使用常量时间比对校验通用 HMAC-SHA256 十六进制签名（防御时序侧信道攻击）
     *
     * @param secret            加签密钥
     * @param content           原始报文内容
     * @param expectedSignature 待校验十六进制签名（大小写不敏感）
     * @return true 若签名严格匹配
     */
    public static boolean verifyHmacSha256Hex(String secret, String content, String expectedSignature) {
        if (secret == null || secret.isBlank() || expectedSignature == null || expectedSignature.isBlank()) {
            return false;
        }
        String computed = signHmacSha256Hex(secret, content);
        return constantTimeHexEquals(computed, expectedSignature);
    }

    private static boolean constantTimeHexEquals(String computed, String expected) {
        if (computed == null || expected == null) {
            return false;
        }
        byte[] computedBytes = computed.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(computedBytes, expectedBytes);
    }

    /**
     * 计算通用 HMAC-SHA256 Base64 摘要签名
     *
     * @param secret  加签密钥
     * @param content 待签名报文内容
     * @return Base64 编码的签名字符串
     */
    public static String signHmacSha256Base64(String secret, String content) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM));
            byte[] signData = mac.doFinal((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception ex) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR,
                    "计算 HMAC-SHA256 Base64 签名失败: " + ex.getMessage(), ex);
        }
    }
}
