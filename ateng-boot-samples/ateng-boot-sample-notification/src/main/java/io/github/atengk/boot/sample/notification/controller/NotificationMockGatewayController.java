package io.github.atengk.boot.sample.notification.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationChannelProperties;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.notification.util.NotificationSignUtil;
import io.github.atengk.boot.sample.notification.service.MockWebhookRecordStore;
import io.github.atengk.boot.sample.notification.vo.MockWebhookRecordVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 离线仿真 Webhook 网关控制器（仅在非 local Profile 下装配，保持完全无状态）
 * 内置企业微信、钉钉、飞书与通用 Webhook 协议验签、报文录制审计与清空接口，实现开箱即用零外部依赖体验
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Profile("!local")
@RestController
@RequestMapping("/sample/notification/mock-gateway")
@RequiredArgsConstructor
public class NotificationMockGatewayController {

    private static final String LEGACY_PLAINTEXT_SECRET_HEADER = "x-webhook-secret";
    private static final long GENERIC_REPLAY_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();

    private final NotificationProperties notificationProperties;
    private final NotificationChannelRegistry channelRegistry;
    private final MockWebhookRecordStore recordStore;

    /**
     * 模拟企业微信群机器人 Webhook 接收端点
     *
     * @param rawBody 原始 JSON 请求体
     * @param request HTTP 请求对象
     * @return 企业微信官方标准响应 JSON
     */
    @PostMapping("/wecom")
    public ResponseEntity<Map<String, Object>> receiveWeCom(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        recordRequest(Platform.WECOM, request, rawBody, true, "企业微信报文接收成功");
        return ResponseEntity.ok(Map.of("errcode", 0, "errmsg", "ok"));
    }

    /**
     * 模拟钉钉群机器人 Webhook 接收端点（校验无二次编码且 HMAC-SHA256 签名合法）
     *
     * @param timestamp 查询参数毫秒级时间戳
     * @param sign      查询参数签名
     * @param rawBody   原始 JSON 请求体
     * @param request   HTTP 请求对象
     * @return 钉钉官方标准响应 JSON
     */
    @PostMapping("/dingtalk")
    public ResponseEntity<Map<String, Object>> receiveDingTalk(
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String sign,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString != null && queryString.contains("%25")) {
            recordRequest(Platform.DINGTALK, request, rawBody, false, "检测到钉钉签名疑似二次 URL 编码 (%25)");
            return ResponseEntity.ok(Map.of("errcode", 310000, "errmsg", "sign double encoded"));
        }

        Set<String> secrets = resolveConfiguredSecrets(Platform.DINGTALK);
        if (!secrets.isEmpty()) {
            boolean verified = verifyDingTalkAgainstSecrets(secrets, timestamp, sign, queryString);
            if (!verified) {
                recordRequest(Platform.DINGTALK, request, rawBody, false, "钉钉 URL 查询参数签名校验未通过");
                return ResponseEntity.ok(Map.of("errcode", 310000, "errmsg", "sign not match"));
            }
        }

        recordRequest(Platform.DINGTALK, request, rawBody, true, "钉钉报文与签名校验通过");
        return ResponseEntity.ok(Map.of("errcode", 0, "errmsg", "ok"));
    }

    /**
     * 模拟飞书群机器人 Webhook 接收端点（校验请求体 HMAC-SHA256 签名）
     *
     * @param rawBody 原始 JSON 请求体
     * @param request HTTP 请求对象
     * @return 飞书官方标准响应 JSON
     */
    @PostMapping("/feishu")
    public ResponseEntity<Map<String, Object>> receiveFeishu(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        Set<String> secrets = resolveConfiguredSecrets(Platform.FEISHU);
        if (!secrets.isEmpty()) {
            boolean verified = verifyFeishuAgainstSecrets(secrets, rawBody);
            if (!verified) {
                recordRequest(Platform.FEISHU, request, rawBody, false, "飞书请求体签名校验未通过");
                return ResponseEntity.ok(Map.of("code", 19021, "msg", "sign match fail"));
            }
        }

        recordRequest(Platform.FEISHU, request, rawBody, true, "飞书报文与签名校验通过");
        return ResponseEntity.ok(Map.of("code", 0, "msg", "success"));
    }

    /**
     * 模拟通用自定义 Webhook 接收端点（拒绝明文密钥头，校验 X-Webhook-Timestamp 防重放时间窗与 X-Signature-SHA256 签名）
     *
     * @param rawBody 原始 JSON 请求体
     * @param request HTTP 请求对象
     * @return 通用网关响应 JSON
     */
    @PostMapping("/generic")
    public ResponseEntity<Map<String, Object>> receiveGeneric(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        if (request.getHeader(LEGACY_PLAINTEXT_SECRET_HEADER) != null) {
            recordRequest(Platform.GENERIC, request, rawBody, false, "安全拦截：禁止携带明文请求头 X-Webhook-Secret");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", "forbidden plaintext header X-Webhook-Secret"));
        }

        Set<String> secrets = resolveConfiguredSecrets(Platform.GENERIC);
        if (!secrets.isEmpty()) {
            String tsHeader = request.getHeader(GenericWebhookNotificationProvider.HEADER_WEBHOOK_TIMESTAMP);
            String sigHeader = request.getHeader(GenericWebhookNotificationProvider.HEADER_SIGNATURE_SHA256);
            boolean verified = verifyGenericAgainstSecrets(secrets, tsHeader, sigHeader, rawBody);
            if (!verified) {
                recordRequest(Platform.GENERIC, request, rawBody, false, "通用网关防重放时间戳或 HMAC 签名校验未通过");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "invalid webhook signature or expired timestamp"));
            }
        }

        recordRequest(Platform.GENERIC, request, rawBody, true, "通用网关防重放签名校验通过");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 查询离线仿真网关录制的全部外发报文审计记录（支持按平台类型过滤）
     *
     * @param platform 可选平台过滤条件（WECOM / DINGTALK / FEISHU / GENERIC）
     * @return 按接收顺序排列的录制报文列表
     */
    @GetMapping("/records")
    public Result<List<MockWebhookRecordVO>> listRecords(@RequestParam(required = false) Platform platform) {
        return Result.success(recordStore.list(platform != null ? platform.name() : null));
    }

    /**
     * 清空离线仿真网关录制的所有审计报文记录
     *
     * @return 本次清理的记录条数
     */
    @DeleteMapping("/records")
    public Result<Integer> clearRecords() {
        return Result.success(recordStore.clear());
    }

    private void recordRequest(Platform platform, HttpServletRequest request, String rawBody,
                               boolean signatureVerified, String detailMessage) {
        MockWebhookRecordVO record = MockWebhookRecordVO.builder()
                .id(IdUtil.fastSimpleUUID())
                .platform(platform.name())
                .path(request.getRequestURI())
                .queryString(request.getQueryString())
                .headers(extractSafeHeaders(request))
                .payload(parsePayloadQuietly(rawBody))
                .signatureVerified(signatureVerified)
                .detailMessage(detailMessage)
                .receivedAt(LocalDateTime.now())
                .build();
        recordStore.append(record);
        log.debug("MockGateway recorded webhook [platform={}, path={}, verified={}]",
                platform, request.getRequestURI(), signatureVerified);
    }

    private Map<String, String> extractSafeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return headers;
        }
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!LEGACY_PLAINTEXT_SECRET_HEADER.equals(lower)) {
                    headers.put(lower, request.getHeader(name));
                }
            }
        }
        return headers;
    }

    private Object parsePayloadQuietly(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.getObjectMapper().readValue(rawBody, Object.class);
        } catch (Exception ex) {
            log.debug("MockGateway failed to parse JSON body, retaining raw payload: {}", ex.getMessage());
            return rawBody;
        }
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

    private boolean verifyDingTalkAgainstSecrets(Set<String> secrets, String timestamp,
                                                 String decodedSign, String queryString) {
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

    private boolean verifyFeishuAgainstSecrets(Set<String> secrets, String rawBody) {
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

    private boolean verifyGenericAgainstSecrets(Set<String> secrets, String tsHeader,
                                                String sigHeader, String rawBody) {
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
