package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
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
@RequiredArgsConstructor
@RequestMapping("${ateng.boot.sms.callback.path-prefix:/ateng-sms/callback}")
public class SmsCallbackController {

    private final SmsChannelRegistry channelRegistry;
    private final ApplicationEventPublisher eventPublisher;

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
    public Map<String, Object> handleCallback(
            @PathVariable("channel") String channelName,
            @RequestBody(required = false) String rawBody,
            @RequestParam(required = false) MultiValueMap<String, String> formParams,
            @RequestHeader HttpHeaders headers) {

        SmsChannel channel = channelRegistry != null ? channelRegistry.get(channelName) : null;
        SmsSupplier supplier = channel != null ? channel.getSupplier() : SmsSupplier.MOCK;
        String actualChannelName = channel != null ? channel.getName() : channelName;

        if (log.isDebugEnabled()) {
            log.debug("[SmsCallbackController] 收到短信渠道 [{}] 异步回调通知 (厂商: {})", actualChannelName, supplier);
        }

        // 解析并广播 Spring 领域事件
        dispatchEvents(actualChannelName, supplier, rawBody, formParams);

        // 构造云厂商标准 ACK 应答
        return buildAckResponse(supplier);
    }

    @SuppressWarnings("unchecked")
    private void dispatchEvents(String channel, SmsSupplier supplier, String rawBody, MultiValueMap<String, String> formParams) {
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
                    log.warn("[SmsCallbackController] 解析 JSON 数组回调报文失败: {}", ex.getMessage());
                }
            } else if (trimmed.startsWith("{")) {
                try {
                    Map<String, Object> map = JsonUtil.parseMap(trimmed, String.class, Object.class);
                    items.add(new LinkedHashMap<>(map));
                } catch (Exception ex) {
                    log.warn("[SmsCallbackController] 解析 JSON 对象回调报文失败: {}", ex.getMessage());
                }
            }
        }

        if (items.isEmpty() && formParams != null && !formParams.isEmpty()) {
            Map<String, Object> formMap = new LinkedHashMap<>();
            formParams.forEach((k, v) -> formMap.put(k, (v != null && !v.isEmpty()) ? v.get(0) : null));
            items.add(formMap);
        }

        if (items.isEmpty()) {
            return;
        }

        for (Map<String, Object> item : items) {
            processItem(channel, supplier, item, rawBody);
        }
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
        if (supplier == SmsSupplier.ALIYUN) {
            return item.containsKey("content") && !item.containsKey("err_code") && !item.containsKey("report_time");
        }
        if (supplier == SmsSupplier.TENCENT) {
            return item.containsKey("text");
        }
        if (supplier == SmsSupplier.HUAWEI) {
            return item.containsKey("body");
        }
        // Generic / Mock: 若包含 content 且不包含 err_code/status 则视作上行回复
        return item.containsKey("content") && !item.containsKey("status") && !item.containsKey("err_code");
    }

    private SmsMoMessageEvent extractMoEvent(String channel, SmsSupplier supplier, Map<String, Object> item, String rawPayload) {
        String phone;
        String content;
        String signName = null;

        if (supplier == SmsSupplier.ALIYUN) {
            phone = getStr(item, "phone_number");
            content = getStr(item, "content");
            signName = getStr(item, "sign_name");
        } else if (supplier == SmsSupplier.TENCENT) {
            phone = getStr(item, "mobile");
            content = getStr(item, "text");
            signName = getStr(item, "sign");
        } else if (supplier == SmsSupplier.HUAWEI) {
            phone = getStr(item, "from");
            content = getStr(item, "body");
        } else {
            phone = item.containsKey("phone") ? getStr(item, "phone") : getStr(item, "mobile");
            content = item.containsKey("content") ? getStr(item, "content") : getStr(item, "text");
            signName = getStr(item, "signName");
        }

        if (phone == null || phone.isBlank()) {
            return null;
        }

        return new SmsMoMessageEvent(this, channel, supplier, phone, content, signName, Instant.now(), rawPayload);
    }

    private SmsDeliveryReportEvent extractReportEvent(String channel, SmsSupplier supplier, Map<String, Object> item, String rawPayload) {
        String phone;
        boolean success;
        String resultCode;
        String resultMessage;
        String bizId;

        if (supplier == SmsSupplier.ALIYUN) {
            phone = getStr(item, "phone_number");
            resultCode = getStr(item, "err_code");
            resultMessage = getStr(item, "err_msg");
            bizId = getStr(item, "biz_id");
            success = Boolean.TRUE.equals(item.get("success")) || "DELIVRD".equalsIgnoreCase(resultCode);
        } else if (supplier == SmsSupplier.TENCENT) {
            phone = getStr(item, "mobile");
            String status = getStr(item, "report_status");
            resultCode = getStr(item, "errmsg");
            resultMessage = getStr(item, "description");
            bizId = getStr(item, "sid");
            success = "SUCCESS".equalsIgnoreCase(status) || "DELIVRD".equalsIgnoreCase(resultCode);
        } else if (supplier == SmsSupplier.HUAWEI) {
            phone = item.containsKey("originTo") ? getStr(item, "originTo") : getStr(item, "to");
            resultCode = getStr(item, "status");
            resultMessage = getStr(item, "statusDesc");
            bizId = getStr(item, "smsMsgId");
            success = "DELIVRD".equalsIgnoreCase(resultCode);
        } else {
            phone = item.containsKey("phone") ? getStr(item, "phone") : getStr(item, "mobile");
            resultCode = item.containsKey("resultCode") ? getStr(item, "resultCode") : getStr(item, "status");
            resultMessage = getStr(item, "resultMessage");
            bizId = item.containsKey("bizId") ? getStr(item, "bizId") : getStr(item, "sid");
            success = Boolean.TRUE.equals(item.get("success")) || "DELIVRD".equalsIgnoreCase(resultCode);
        }

        if (phone == null || phone.isBlank()) {
            return null;
        }

        return new SmsDeliveryReportEvent(this, channel, supplier, phone, success, resultCode, resultMessage, bizId, Instant.now(), rawPayload);
    }

    private String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val).trim() : null;
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
