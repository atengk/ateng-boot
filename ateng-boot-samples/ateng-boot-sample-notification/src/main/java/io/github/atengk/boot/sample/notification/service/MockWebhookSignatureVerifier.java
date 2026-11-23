package io.github.atengk.boot.sample.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationChannelProperties;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.util.NotificationSignUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 离线仿真 Webhook 网关多平台签名校验器（仅在非 local Profile 下装配，保持无状态）
 * 从配置属性与运行时注册中心聚合对应平台密钥，分别执行钉钉 URL 签名、飞书请求体签名与通用防重放 HMAC 验签
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Component
@Profile("!local")
@RequiredArgsConstructor
public class MockWebhookSignatureVerifier {

    private static final long GENERIC_REPLAY_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();

    private final NotificationProperties notificationProperties;
    private final NotificationChannelRegistry channelRegistry;

    /**
     * 校验钉钉出站 URL 查询参数签名（若未配置任何钉钉密钥则直接放行）
     *
     * @param timestamp   毫秒级时间戳查询参数
     * @param decodedSign 容器解码后的签名参数
     * @param queryString 原始查询串
     * @return true 若未配置密钥或签名匹配
     */
    public boolean verifyDingTalk(String timestamp, String decodedSign, String queryString) {
        Set<String> secrets = resolveConfiguredSecrets(Platform.DINGTALK);
        if (secrets.isEmpty()) {
            return true;
        }
        if (timestamp == null || timestamp.isBlank()) {
            return false;
        }
        long tsMillis;
        try {
            tsMillis = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            log.debug("MockGateway rejected invalid DingTalk timestamp [{}]: {}", timestamp, ex.getMessage());
            return false;
        }
        String rawSign = extractRawQueryParam(queryString, "sign");
        for (String secret : secrets) {
            String expectedEncoded = NotificationSignUtil.signDingTalk(secret, tsMillis);
            if (expectedEncoded == null) {
                continue;
            }
            if (rawSign != null && constantTimeEquals(expectedEncoded, rawSign)) {
                return true;
            }
            if (rawSign == null && decodedSign != null && !decodedSign.isBlank()) {
                String expectedDecoded = URLDecoder.decode(expectedEncoded, StandardCharsets.UTF_8);
                if (constantTimeEquals(expectedDecoded, decodedSign)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 校验飞书请求体签名（若未配置任何飞书密钥则直接放行）
     *
     * @param rawBody 原始 JSON 请求体
     * @return true 若未配置密钥或签名匹配
     */
    public boolean verifyFeishu(String rawBody) {
        Set<String> secrets = resolveConfiguredSecrets(Platform.FEISHU);
        if (secrets.isEmpty()) {
            return true;
        }
        if (rawBody == null || rawBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JsonUtil.getObjectMapper().readTree(rawBody);
            String timestamp = root.path("timestamp").asText(null);
            String sign = root.path("sign").asText(null);
            if (timestamp == null || timestamp.isBlank() || sign == null || sign.isBlank()) {
                return false;
            }
            long tsSeconds = Long.parseLong(timestamp.trim());
            for (String secret : secrets) {
                String expected = NotificationSignUtil.signFeishu(secret, tsSeconds);
                if (expected != null && constantTimeEquals(expected, sign)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.debug("MockGateway failed to verify Feishu signature: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 校验通用自定义 Webhook 防重放时间戳与 HMAC-SHA256 签名（若未配置任何通用密钥则直接放行）
     *
     * @param tsHeader  时间戳请求头
     * @param sigHeader 签名请求头
     * @param rawBody   原始请求体
     * @return true 若未配置密钥或时间戳在 5 分钟窗口内且签名匹配
     */
    public boolean verifyGeneric(String tsHeader, String sigHeader, String rawBody) {
        Set<String> secrets = resolveConfiguredSecrets(Platform.GENERIC);
        if (secrets.isEmpty()) {
            return true;
        }
        if (tsHeader == null || tsHeader.isBlank() || sigHeader == null || sigHeader.isBlank()) {
            return false;
        }
        long tsMillis;
        try {
            tsMillis = Long.parseLong(tsHeader.trim());
        } catch (NumberFormatException ex) {
            log.debug("MockGateway rejected invalid Generic timestamp header [{}]: {}", tsHeader, ex.getMessage());
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - tsMillis) > GENERIC_REPLAY_WINDOW_MILLIS) {
            log.debug("MockGateway rejected expired Generic webhook timestamp [{}]", tsMillis);
            return false;
        }
        for (String secret : secrets) {
            if (NotificationSignUtil.verifyGenericWebhook(secret, tsMillis, rawBody, sigHeader)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveConfiguredSecrets(Platform platform) {
        Set<String> secrets = new LinkedHashSet<>();
        if (notificationProperties != null && notificationProperties.getChannels() != null) {
            for (NotificationChannelProperties channelProps : notificationProperties.getChannels().values()) {
                if (channelProps != null && channelProps.getPlatform() == platform
                        && channelProps.getSecret() != null && !channelProps.getSecret().isBlank()) {
                    secrets.add(channelProps.getSecret().trim());
                }
            }
        }
        if (channelRegistry != null) {
            for (NotificationChannel channel : channelRegistry.getAll().values()) {
                if (channel != null && channel.getPlatform() == platform
                        && channel.getSecret() != null && !channel.getSecret().isBlank()) {
                    secrets.add(channel.getSecret().trim());
                }
            }
        }
        return secrets;
    }

    private String extractRawQueryParam(String queryString, String key) {
        if (queryString == null || queryString.isBlank()) {
            return null;
        }
        String prefix = key + "=";
        for (String pair : queryString.split("&")) {
            if (pair.startsWith(prefix)) {
                return pair.substring(prefix.length());
            }
        }
        return null;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.trim().getBytes(StandardCharsets.UTF_8),
                b.trim().getBytes(StandardCharsets.UTF_8));
    }
}
