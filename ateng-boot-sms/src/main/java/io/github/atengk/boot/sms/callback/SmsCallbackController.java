package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsCallbackProperties;
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

import java.time.Instant;
import java.util.*;

/**
 * 统一运营商短信异步送达回执与用户上行回复 Webhook 控制器
 * 统一接入端点：${ateng.boot.sms.callback.path-prefix:/ateng-sms/callback}/{channel}
 * 自动识别并转译各云厂商推送报文，向 Spring 容器广播标准领域事件
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
    private final SmsCallbackProperties callbackProperties;

    public SmsCallbackController(SmsChannelRegistry channelRegistry,
                                 ApplicationEventPublisher eventPublisher,
                                 SmsCallbackProperties callbackProperties) {
        this.channelRegistry = channelRegistry;
        this.eventPublisher = eventPublisher;
        this.callbackProperties = callbackProperties != null ? callbackProperties : new SmsCallbackProperties();
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

        if (rawBody != null && !rawBody.isBlank()) {
            String trimmed = rawBody.trim();
            if (trimmed.startsWith("[")) {
                try {
                    List<Map> list = JsonUtil.parseList(trimmed, Map.class);
                    for (Map m : list) {
                        items.add(new LinkedHashMap<>(m));
                    }
                } catch (Exception ex) {
                    log.warn("[SmsCallbackController] 解析 JSON 数组回调报文异常: {}", ex.getMessage());
                }
            } else if (trimmed.startsWith("{")) {
                try {
                    Map<String, Object> map = JsonUtil.parseMap(trimmed, String.class, Object.class);
                    items.add(new LinkedHashMap<>(map));
                } catch (Exception ex) {
                    log.warn("[SmsCallbackController] 解析 JSON 对象回调报文异常: {}", ex.getMessage());
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

    private void processItem(String channel, SmsSupplier supplier, Map<String, Object> item, String rawPayload) {
        if (item == null || item.isEmpty()) {
            return;
        }

        // 1. 判断是否为用户上行回复短信 (MO)
        if (isMoMessage(supplier, item)) {
            SmsMoMessageEvent moEvent = extractMoEvent(channel, supplier, item, rawPayload);
            if (moEvent != null && eventPublisher != null) {
                if (log.isInfoEnabled()) {
                    log.info("[SmsCallbackController] 捕获用户上行回复短信 (MO) -> 渠道: {}, 号码: {}, 内容: {}",
                            channel, DesensitizeUtil.mobilePhone(moEvent.getPhone()), moEvent.getContent());
                }
                eventPublisher.publishEvent(moEvent);
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
            default -> Map.of("code", 0, "message", "OK");
        };
    }
}
