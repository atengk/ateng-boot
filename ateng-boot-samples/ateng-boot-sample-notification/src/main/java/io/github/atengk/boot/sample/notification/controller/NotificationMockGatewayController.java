package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.sample.notification.service.MockWebhookRecordStore;
import io.github.atengk.boot.sample.notification.service.MockWebhookSignatureVerifier;
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

import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线仿真 Webhook 网关控制器（仅在非 local Profile 下装配，保持完全无状态）
 * 对接企业微信、钉钉、飞书与通用 Webhook 接收端点，并将验签委托至 {@link MockWebhookSignatureVerifier}、审计记录委托至 {@link MockWebhookRecordStore}
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

    private final MockWebhookSignatureVerifier signatureVerifier;
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

        if (!signatureVerifier.verifyDingTalk(timestamp, sign, queryString)) {
            recordRequest(Platform.DINGTALK, request, rawBody, false, "钉钉 URL 查询参数签名校验未通过");
            return ResponseEntity.ok(Map.of("errcode", 310000, "errmsg", "sign not match"));
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
        if (!signatureVerifier.verifyFeishu(rawBody)) {
            recordRequest(Platform.FEISHU, request, rawBody, false, "飞书请求体签名校验未通过");
            return ResponseEntity.ok(Map.of("code", 19021, "msg", "sign match fail"));
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

        String tsHeader = request.getHeader(GenericWebhookNotificationProvider.HEADER_WEBHOOK_TIMESTAMP);
        String sigHeader = request.getHeader(GenericWebhookNotificationProvider.HEADER_SIGNATURE_SHA256);
        if (!signatureVerifier.verifyGeneric(tsHeader, sigHeader, rawBody)) {
            recordRequest(Platform.GENERIC, request, rawBody, false, "通用网关防重放时间戳或 HMAC 签名校验未通过");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "invalid webhook signature or expired timestamp"));
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
}
