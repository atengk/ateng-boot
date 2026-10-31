package io.github.atengk.boot.mqtt.model;

import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 死信记录强类型承载模型
 * 封装死信来源主题、原始业务主题、原始载荷、QoS、异常类型与消息、死信时间戳、重放次数及用户扩展标头
 *
 * @author Ateng
 * @since 2026-10-31
 */
public record MqttDeadLetterRecord(
        String dltTopic,
        String originalTopic,
        byte[] payload,
        int qos,
        String exceptionClass,
        String exceptionMessage,
        long dltTimestamp,
        int replayCount,
        Map<String, String> userProperties
) {

    public static final String HEADER_ORIGINAL_TOPIC = "x-original-topic";
    public static final String HEADER_EXCEPTION_CLASS = "x-exception-class";
    public static final String HEADER_EXCEPTION_MESSAGE = "x-exception-message";
    public static final String HEADER_DLT_TIMESTAMP = "x-dlt-timestamp";
    public static final String HEADER_REPLAYED = "X-Dead-Letter-Replayed";
    public static final String HEADER_REPLAY_COUNT = "X-Replay-Count";
    public static final String HEADER_REPLAY_TIMESTAMP = "X-Replay-Timestamp";

    public MqttDeadLetterRecord {
        userProperties = userProperties != null ? Collections.unmodifiableMap(userProperties) : Collections.emptyMap();
        payload = payload != null ? payload : new byte[0];
    }

    /**
     * 将载荷按 UTF-8 解码为文本字符串
     *
     * @return 文本载荷
     */
    public String getPayloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * 判断当前死信报文是否此前已被重放过
     *
     * @return true 若为重放流转报文
     */
    public boolean isReplayed() {
        return replayCount > 0;
    }

    /**
     * 从接收到的原生 MQTT 报文中解析提取死信结构体
     *
     * @param dltTopic 死信接收主题
     * @param message  MQTT 报文
     * @return 强类型死信记录
     */
    public static MqttDeadLetterRecord fromMqttMessage(String dltTopic, MqttMessage message) {
        byte[] payload = message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
        int qos = message != null ? message.getQos() : 1;

        Map<String, String> userProps = new LinkedHashMap<>();
        String originalTopic = null;
        String exceptionClass = null;
        String exceptionMessage = null;
        long dltTimestamp = 0L;
        int replayCount = 0;

        if (message != null && message.getProperties() != null && message.getProperties().getUserProperties() != null) {
            List<UserProperty> props = message.getProperties().getUserProperties();
            for (UserProperty prop : props) {
                String key = prop.getKey();
                String value = prop.getValue();
                userProps.put(key, value);

                if (HEADER_ORIGINAL_TOPIC.equalsIgnoreCase(key)) {
                    originalTopic = value;
                } else if (HEADER_EXCEPTION_CLASS.equalsIgnoreCase(key)) {
                    exceptionClass = value;
                } else if (HEADER_EXCEPTION_MESSAGE.equalsIgnoreCase(key)) {
                    exceptionMessage = value;
                } else if (HEADER_DLT_TIMESTAMP.equalsIgnoreCase(key)) {
                    try {
                        dltTimestamp = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {}
                } else if (HEADER_REPLAY_COUNT.equalsIgnoreCase(key)) {
                    try {
                        replayCount = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 若标头未携带 originalTopic，保底通过剥离 /DLT 后缀尝试还原
        if (!StringUtils.hasText(originalTopic) && StringUtils.hasText(dltTopic)) {
            if (dltTopic.endsWith("/DLT")) {
                originalTopic = dltTopic.substring(0, dltTopic.length() - 4);
            } else {
                originalTopic = dltTopic;
            }
        }

        return new MqttDeadLetterRecord(
                dltTopic,
                originalTopic,
                payload,
                qos,
                exceptionClass,
                exceptionMessage,
                dltTimestamp,
                replayCount,
                userProps
        );
    }

    /**
     * 从 MQTT 报文中快速提取重放计数值（防二次雪崩熔断探测）
     *
     * @param message MQTT 报文
     * @return 已重放次数，若未重放则返回 0
     */
    public static int extractReplayCount(MqttMessage message) {
        if (message == null || message.getProperties() == null || message.getProperties().getUserProperties() == null) {
            return 0;
        }
        for (UserProperty prop : message.getProperties().getUserProperties()) {
            if (HEADER_REPLAY_COUNT.equalsIgnoreCase(prop.getKey())) {
                try {
                    return Integer.parseInt(prop.getValue());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }
}
