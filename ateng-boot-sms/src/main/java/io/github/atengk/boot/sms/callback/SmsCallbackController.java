package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import io.github.atengk.boot.sms.guard.SmsBlacklistProvider;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsCallbackProperties;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * 统一运营商短信异步送达回执与用户上行回复 Webhook 控制器
 * 统一接入端点：${ateng.boot.sms.callback.path-prefix:/ateng-sms/callback}/{channel}
 * 自动识别并转译各云厂商推送报文，向 Spring 容器广播标准领域事件
 * 支持捕获用户上行退订回复 (TD/退订/T) 自动闭环将手机号写入出站黑名单
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@RestController
@RequestMapping("${ateng.boot.sms.callback.path-prefix:/ateng-sms/callback}")
public class SmsCallbackController {

    private final SmsChannelRegistry channelRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final SmsBlacklistProvider blacklistProvider;
    private final SmsCallbackProperties callbackProperties;
    private final SmsGuardProperties guardProperties;

    public SmsCallbackController(SmsChannelRegistry channelRegistry,
                                 ApplicationEventPublisher eventPublisher,
                                 SmsBlacklistProvider blacklistProvider,
                                 SmsProperties properties) {
        this.channelRegistry = channelRegistry;
        this.eventPublisher = eventPublisher;
        this.blacklistProvider = blacklistProvider;
        this.callbackProperties = properties != null && properties.getCallback() != null ? properties.getCallback() : new SmsCallbackProperties();
        this.guardProperties = properties != null && properties.getGuard() != null ? properties.getGuard() : new SmsGuardProperties();
    }

    public SmsCallbackController(SmsChannelRegistry channelRegistry,
                                 ApplicationEventPublisher eventPublisher,
                                 SmsCallbackProperties callbackProperties) {
        this.channelRegistry = channelRegistry;
        this.eventPublisher = eventPublisher;
        this.blacklistProvider = null;
        this.callbackProperties = callbackProperties != null ? callbackProperties : new SmsCallbackProperties();
        this.guardProperties = new SmsGuardProperties();
    }

    public SmsCallbackController(SmsChannelRegistry channelRegistry,
                                 ApplicationEventPublisher eventPublisher) {
        this(channelRegistry, eventPublisher, null);
    }

    /**
     * 接收统一短信回调请求（支持 JSON Body 与表单提交）
     *
     * @param channelName 渠道名称
     * @param rawBody     原始请求体
     * @param formParams  表单参数（备用兼容）
     * @param headers     请求头
     * @return 厂商规范约定的响应应答报文
     */
    @PostMapping(value = "/{channel}", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<Map<String, Object>> handleCallback(
            @PathVariable("channel") String channelName,
            @RequestBody(required = false) String rawBody,
            @RequestParam(required = false) MultiValueMap<String, String> formParams,
            @RequestHeader HttpHeaders headers) {

        // 1. 渠道合法性前置防卫 (Fail-Fast)
        SmsChannel channel = channelRegistry != null ? channelRegistry.get(channelName) : null;
        if (channel == null && !"mock".equalsIgnoreCase(channelName)) {
            log.warn("[SmsCallbackController] 拒绝非法回调：未找到短信渠道 [{}]", channelName);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", "Channel not found: " + channelName));
        }

        // 2. 回调安全令牌防伪防刷校验 (若配置了 callback.token)
        if (StringUtils.hasText(callbackProperties.getToken())) {
            String token = headers.getFirst("X-Callback-Token");
            if (!StringUtils.hasText(token) && formParams != null) {
                token = formParams.getFirst("token");
            }
            if (!callbackProperties.getToken().equals(token)) {
                log.warn("[SmsCallbackController] 渠道 [{}] 回调安全令牌校验失败，拒绝伪造请求", channelName);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid callback token"));
            }
        }

        SmsSupplier supplier = channel != null ? channel.getSupplier() : SmsSupplier.MOCK;
        String actualChannelName = channel != null ? channel.getName() : channelName;

        if (log.isDebugEnabled()) {
            log.debug("[SmsCallbackController] 收到短信渠道 [{}] 异步回调通知 (厂商: {})", actualChannelName, supplier);
        }

        // 3. 解析请求体明细，若格式非法则 Fail-Fast 返回 400，严禁静默吞咽异常误报成功
        List<Map<String, Object>> items = parseCallbackPayload(rawBody, formParams);
        if (items.isEmpty() && StringUtils.hasText(rawBody)) {
            log.warn("[SmsCallbackController] 渠道 [{}] 回调报文格式无法识别: {}", actualChannelName, rawBody);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", "Invalid callback payload format"));
        }

        // 4. 解析并广播 Spring 领域事件
        for (Map<String, Object> item : items) {
            processItem(actualChannelName, supplier, item, rawBody);
        }

        // 5. 构造云厂商标准 ACK 应答
        return ResponseEntity.ok(buildAckResponse(supplier));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCallbackPayload(String rawBody, MultiValueMap<String, String> formParams) {
        List<Map<String, Object>> items = new ArrayList<>();

        // 云片网特殊表单参数支持 (sms_status / sms_reply 挂载 JSON 数组)
        if (formParams != null && !formParams.isEmpty()) {
            if (formParams.containsKey("sms_status")) {
                appendJsonArrayItems(formParams.getFirst("sms_status"), "云片网 sms_status", items);
            } else if (formParams.containsKey("sms_reply")) {
                appendJsonArrayItems(formParams.getFirst("sms_reply"), "云片网 sms_reply", items);
            }
        }

        if (items.isEmpty() && rawBody != null && !rawBody.isBlank()) {
            String trimmed = rawBody.trim();
            if (trimmed.startsWith("[")) {
                appendJsonArrayItems(trimmed, "JSON 数组", items);
            } else if (trimmed.startsWith("{")) {
                try {
                    Map<String, Object> map = JsonUtil.parseMap(trimmed, String.class, Object.class);
                    boolean unnested = false;
                    for (String listKey : List.of("reports", "replies", "data", "list", "items")) {
                        Object val = map.get(listKey);
                        if (val instanceof List<?> list) {
                            for (Object el : list) {
                                if (el instanceof Map m) {
                                    items.add(new LinkedHashMap<>(m));
                                    unnested = true;
                                }
                            }
                        }
                    }
                    if (!unnested) {
                        items.add(new LinkedHashMap<>(map));
                    }
                } catch (Exception ex) {
                    log.warn("[SmsCallbackController] 解析 JSON 对象回调报文异常: {}", ex.getMessage());
                }
            } else if (trimmed.contains("sms_status=") || trimmed.contains("sms_reply=")) {
                // 云片网 Raw URL-Encoded 格式
                try {
                    String decoded = URLDecoder.decode(trimmed, StandardCharsets.UTF_8);
                    int eqIdx = decoded.indexOf('=');
                    if (eqIdx != -1) {
                        String jsonPart = decoded.substring(eqIdx + 1).trim();
                        appendJsonArrayItems(jsonPart, "URL-Encoded 云片网", items);
                    }
                } catch (Exception ex) {
                    log.warn("[SmsCallbackController] 解析 URL-Encoded 云片网报文异常: {}", ex.getMessage());
                }
            }
        }

        if (items.isEmpty() && formParams != null && !formParams.isEmpty()) {
            Map<String, Object> formMap = new LinkedHashMap<>();
            formParams.forEach((k, v) -> formMap.put(k, (v != null && !v.isEmpty()) ? v.get(0) : null));
            items.add(formMap);
        }

        return items;
    }

    @SuppressWarnings("unchecked")
    private void appendJsonArrayItems(String json, String sourceName, List<Map<String, Object>> targetList) {
        if (!StringUtils.hasText(json)) {
            return;
        }
        try {
            List<Map> list = JsonUtil.parseList(json.trim(), Map.class);
            for (Map m : list) {
                targetList.add(new LinkedHashMap<>(m));
            }
        } catch (Exception ex) {
            log.warn("[SmsCallbackController] 解析 {} 回调报文异常: {}", sourceName, ex.getMessage());
        }
    }

    private void processItem(String channel, SmsSupplier supplier, Map<String, Object> item, String rawPayload) {
        if (item == null || item.isEmpty()) {
            return;
        }

        // 1. 判断是否为用户上行回复短信 (MO)
        if (isMoMessage(supplier, item)) {
            SmsMoMessageEvent moEvent = extractMoEvent(channel, supplier, item, rawPayload);
            if (moEvent != null) {
                // 上行退订指令检测：TD / 退订 / T
                if (guardProperties != null && guardProperties.isAutoBlacklistOnUnsubscribe() && blacklistProvider != null) {
                    String moContent = moEvent.getContent();
                    if (moContent != null) {
                        String trimmed = moContent.trim();
                        if ("TD".equalsIgnoreCase(trimmed) || "退订".equals(trimmed) || "T".equalsIgnoreCase(trimmed)) {
                            String reason = "用户通过上行回复 [" + trimmed + "] 触发自动退订";
                            blacklistProvider.add(moEvent.getPhone(), reason);
                            log.info("[SmsCallbackController] 捕获用户上行退订指令 [{}]，自动将手机号 [{}] 纳入出站黑名单",
                                    trimmed, DesensitizeUtil.mobilePhone(moEvent.getPhone()));
                        }
                    }
                }

                if (eventPublisher != null) {
                    if (log.isInfoEnabled()) {
                        log.info("[SmsCallbackController] 捕获用户上行回复短信 (MO) -> 渠道: {}, 号码: {}, 内容: {}",
                                channel, DesensitizeUtil.mobilePhone(moEvent.getPhone()), moEvent.getContent());
                    }
                    eventPublisher.publishEvent(moEvent);
                }
            }
            return;
        }

        // 2. 运营商送达回执报告 (Delivery Report)
        SmsDeliveryReportEvent reportEvent = extractReportEvent(channel, supplier, item, rawPayload);
        if (reportEvent != null && eventPublisher != null) {
            if (log.isInfoEnabled()) {
                log.info("[SmsCallbackController] 捕获运营商短信送达回执 -> 渠道: {}, 号码: {}, 状态: {}, 结果码: {}",
                        channel, DesensitizeUtil.mobilePhone(reportEvent.getPhone()),
                        reportEvent.isSuccess() ? "送达成功" : "送达失败",
                        reportEvent.getResultCode());
            }
            eventPublisher.publishEvent(reportEvent);
        }
    }

    private boolean isMoMessage(SmsSupplier supplier, Map<String, Object> item) {
        return switch (supplier != null ? supplier : SmsSupplier.MOCK) {
            case ALIYUN -> item.containsKey("content") && !item.containsKey("err_code") && !item.containsKey("report_time");
            case TENCENT -> item.containsKey("text");
            case HUAWEI -> item.containsKey("body");
            case YUNPIAN -> item.containsKey("reply_time") || (item.containsKey("text") && !item.containsKey("report_status"));
            case CTYUN -> item.containsKey("content") && !item.containsKey("status") && !item.containsKey("statusCode");
            default -> item.containsKey("content") && !item.containsKey("status") && !item.containsKey("err_code");
        };
    }

    private SmsMoMessageEvent extractMoEvent(String channel, SmsSupplier supplier, Map<String, Object> item, String rawPayload) {
        String phone;
        String content;
        String signName = null;

        SmsSupplier safeSupplier = supplier != null ? supplier : SmsSupplier.MOCK;
        switch (safeSupplier) {
            case ALIYUN -> {
                phone = getStr(item, "phone_number");
                content = getStr(item, "content");
                signName = getStr(item, "sign_name");
            }
            case TENCENT -> {
                phone = getStr(item, "mobile");
                content = getStr(item, "text");
                signName = getStr(item, "sign");
            }
            case HUAWEI -> {
                phone = getStr(item, "from");
                content = getStr(item, "body");
            }
            case YUNPIAN -> {
                phone = getStr(item, "mobile");
                content = getFirstStr(item, "text", "content");
            }
            case CTYUN -> {
                phone = getFirstStr(item, "phoneNumber", "phone", "mobile");
                content = getFirstStr(item, "content", "message");
            }
            default -> {
                phone = getFirstStr(item, "phone_number", "phone", "mobile");
                content = getFirstStr(item, "content", "text", "body");
                signName = getFirstStr(item, "sign_name", "sign", "signName");
            }
        }

        if (phone == null || phone.isBlank()) {
            return null;
        }

        return new SmsMoMessageEvent(this, channel, safeSupplier, phone, content, signName, Instant.now(), rawPayload);
    }

    private SmsDeliveryReportEvent extractReportEvent(String channel, SmsSupplier supplier, Map<String, Object> item, String rawPayload) {
        String phone;
        boolean success;
        String resultCode;
        String resultMessage;
        String bizId;

        SmsSupplier safeSupplier = supplier != null ? supplier : SmsSupplier.MOCK;
        switch (safeSupplier) {
            case ALIYUN -> {
                phone = getStr(item, "phone_number");
                resultCode = getStr(item, "err_code");
                resultMessage = getStr(item, "err_msg");
                bizId = getStr(item, "biz_id");
                success = Boolean.TRUE.equals(item.get("success")) || "DELIVRD".equalsIgnoreCase(resultCode);
            }
            case TENCENT -> {
                phone = getStr(item, "mobile");
                String status = getStr(item, "report_status");
                resultCode = getStr(item, "errmsg");
                resultMessage = getStr(item, "description");
                bizId = getStr(item, "sid");
                success = "SUCCESS".equalsIgnoreCase(status) || "DELIVRD".equalsIgnoreCase(resultCode);
            }
            case HUAWEI -> {
                phone = getFirstStr(item, "originTo", "to");
                resultCode = getStr(item, "status");
                resultMessage = getStr(item, "statusDesc");
                bizId = getStr(item, "smsMsgId");
                success = "DELIVRD".equalsIgnoreCase(resultCode);
            }
            case YUNPIAN -> {
                phone = getStr(item, "mobile");
                String reportStatus = getStr(item, "report_status");
                resultCode = getStr(item, "error_msg");
                resultMessage = getStr(item, "error_msg");
                bizId = getStr(item, "sid");
                success = "SUCCESS".equalsIgnoreCase(reportStatus) || "DELIVRD".equalsIgnoreCase(resultCode);
            }
            case CTYUN -> {
                phone = getFirstStr(item, "phoneNumber", "phone_number", "phone", "mobile", "to");
                resultCode = getFirstStr(item, "status", "statusCode", "code");
                resultMessage = getFirstStr(item, "statusMsg", "message", "msg");
                bizId = getFirstStr(item, "taskId", "bizId", "messageId", "msgId");
                success = "0".equals(resultCode) || "DELIVRD".equalsIgnoreCase(resultCode) || "OK".equalsIgnoreCase(resultCode);
            }
            default -> {
                phone = getFirstStr(item, "phone_number", "phone", "mobile", "to");
                resultCode = getFirstStr(item, "err_code", "resultCode", "status");
                resultMessage = getFirstStr(item, "err_msg", "resultMessage");
                bizId = getFirstStr(item, "biz_id", "bizId", "sid");
                success = Boolean.TRUE.equals(item.get("success")) || "DELIVRD".equalsIgnoreCase(resultCode) || "SUCCESS".equalsIgnoreCase(resultCode);
            }
        }

        if (phone == null || phone.isBlank()) {
            return null;
        }

        return new SmsDeliveryReportEvent(this, channel, safeSupplier, phone, success, resultCode, resultMessage, bizId, Instant.now(), rawPayload);
    }

    private String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val).trim() : null;
    }

    private String getFirstStr(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                String str = String.valueOf(val).trim();
                if (!str.isEmpty()) {
                    return str;
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildAckResponse(SmsSupplier supplier) {
        if (supplier == null) {
            return Map.of("code", 0, "message", "OK");
        }
        return switch (supplier) {
            case ALIYUN -> Map.of("code", 0, "msg", "成功");
            case TENCENT -> Map.of("result", 0, "errmsg", "OK");
            case HUAWEI -> Map.of("result", "0");
            case YUNPIAN -> Map.of("code", 0, "msg", "OK");
            case CTYUN -> Map.of("code", "0", "message", "OK");
            default -> Map.of("code", 0, "message", "OK");
        };
    }
}
