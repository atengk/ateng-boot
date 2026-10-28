package io.github.atengk.boot.mqtt.properties;

import java.nio.charset.StandardCharsets;

/**
 * MQTT 遗嘱消息（Last Will and Testament, LWT）配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttLwtProperties {

    /**
     * 是否启用遗嘱消息
     */
    private boolean enabled = false;

    /**
     * 遗嘱消息目标主题
     */
    private String topic;

    /**
     * 遗嘱消息载荷内容
     */
    private String payload;

    /**
     * 遗嘱消息服务质量等级 (0, 1, 2)
     */
    private int qos = 1;

    /**
     * 是否作为保留消息 (Retain)
     */
    private boolean retain = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public int getQos() {
        return qos;
    }

    public void setQos(int qos) {
        this.qos = qos;
    }

    public boolean isRetain() {
        return retain;
    }

    public void setRetain(boolean retain) {
        this.retain = retain;
    }

    public byte[] getPayloadBytes() {
        return payload != null ? payload.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }
}
