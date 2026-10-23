package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原生特化 JSON 逃生舱通知消息模型
 * 允许调用方直接透传平台专有的原生 Payload 报文结构，规避抽象层约束
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class RawMessage extends NotificationMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 原生 JSON 字符串报文
     */
    private String rawJson;

    /**
     * 原生 Map 键值对报文载荷
     */
    private Map<String, Object> rawPayload;

    public RawMessage(String rawJson) {
        this.rawJson = rawJson;
    }

    public RawMessage(Map<String, Object> rawPayload) {
        this.rawPayload = rawPayload;
    }

    public static RawMessage of(String rawJson) {
        return new RawMessage(rawJson);
    }

    public static RawMessage of(Map<String, Object> rawPayload) {
        return new RawMessage(rawPayload);
    }

    public RawMessage rawJson(String rawJson) {
        this.rawJson = rawJson;
        return this;
    }

    public RawMessage rawPayload(Map<String, Object> rawPayload) {
        this.rawPayload = rawPayload;
        return this;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.RAW;
    }

    @Override
    public void validate() {
        boolean hasJson = rawJson != null && !rawJson.isBlank();
        boolean hasPayload = rawPayload != null && !rawPayload.isEmpty();
        if (!hasJson && !hasPayload) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "原生逃生舱 rawJson 或 rawPayload 至少一项不能为空");
        }
    }

    /**
     * 将原始逃生舱报文提取或解析为键值映射表
     *
     * @return 报文 Map 结构；若为空或解析失败则返回空 Map
     */
    public Map<String, Object> toPayloadMap() {
        if (rawPayload != null && !rawPayload.isEmpty()) {
            return new LinkedHashMap<>(rawPayload);
        }
        if (rawJson != null && !rawJson.isBlank()) {
            Map<String, Object> parsed = JsonUtil.parseMap(rawJson);
            if (parsed != null) {
                return parsed;
            }
        }
        return Collections.emptyMap();
    }
}
