package io.github.atengk.boot.mqtt.will;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * MQTT 客户端遗嘱实体
 * 封装客户端意外宕机时由 Broker 代为广播的目标主题、载荷、QoS 与 Retain 属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttWill {

    private final String topic;
    private final byte[] payload;
    private final int qos;
    private final boolean retained;

    public MqttWill(String topic, byte[] payload, int qos, boolean retained) {
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("MQTT Will topic must not be empty");
        }
        this.topic = topic;
        this.payload = payload != null ? payload : new byte[0];
        this.qos = Math.max(0, Math.min(qos, 2));
        this.retained = retained;
    }

    public static MqttWill of(String topic, String payload) {
        return of(topic, payload != null ? payload.getBytes(StandardCharsets.UTF_8) : new byte[0], 1, false);
    }

    public static MqttWill of(String topic, byte[] payload, int qos, boolean retained) {
        return new MqttWill(topic, payload, qos, retained);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTopic() {
        return topic;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getQos() {
        return qos;
    }

    public boolean isRetained() {
        return retained;
    }

    public static class Builder {
        private String topic;
        private byte[] payload = new byte[0];
        private int qos = 1;
        private boolean retained = false;

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder payload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload != null ? payload.getBytes(StandardCharsets.UTF_8) : new byte[0];
            return this;
        }

        public Builder qos(int qos) {
            this.qos = qos;
            return this;
        }

        public Builder retained(boolean retained) {
            this.retained = retained;
            return this;
        }

        public MqttWill build() {
            return new MqttWill(topic, payload, qos, retained);
        }
    }
}
