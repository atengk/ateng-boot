package io.github.atengk.boot.mqtt.model;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQTT 批量发布消息记录模型
 * 封装发布目标主题、业务载荷、QoS 服务质量、保留消息标志、用户自定义扩展属性及消息有效生命周期
 *
 * @author Ateng
 * @since 2026-10-31
 */
public record MqttPublishRecord(
        String topic,
        Object payload,
        int qos,
        boolean retained,
        Map<String, String> userProperties,
        Duration messageExpiry
) {

    public MqttPublishRecord {
        Assert.hasText(topic, "topic must not be blank");
        Assert.notNull(payload, "payload must not be null");
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("MQTT QoS must be between 0 and 2: " + qos);
        }
        if (messageExpiry != null && messageExpiry.isNegative()) {
            throw new IllegalArgumentException("messageExpiry must not be negative");
        }
        userProperties = userProperties != null ? Collections.unmodifiableMap(userProperties) : Collections.emptyMap();
    }

    public static MqttPublishRecord of(String topic, Object payload) {
        return of(topic, payload, 1, false, null, null);
    }

    public static MqttPublishRecord of(String topic, Object payload, int qos) {
        return of(topic, payload, qos, false, null, null);
    }

    public static MqttPublishRecord of(String topic, Object payload, int qos, boolean retained) {
        return of(topic, payload, qos, retained, null, null);
    }

    public static MqttPublishRecord of(String topic, Object payload, int qos, boolean retained, Duration messageExpiry) {
        return of(topic, payload, qos, retained, null, messageExpiry);
    }

    public static MqttPublishRecord of(String topic, Object payload, int qos, boolean retained, Map<String, String> userProperties, Duration messageExpiry) {
        return new MqttPublishRecord(topic, payload, qos, retained, userProperties, messageExpiry);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String topic;
        private Object payload;
        private int qos = 1;
        private boolean retained = false;
        private Map<String, String> userProperties;
        private Duration messageExpiry;

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder payload(Object payload) {
            this.payload = payload;
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

        public Builder userProperty(String key, String value) {
            if (this.userProperties == null) {
                this.userProperties = new LinkedHashMap<>();
            }
            this.userProperties.put(key, value);
            return this;
        }

        public Builder userProperties(Map<String, String> userProperties) {
            if (userProperties != null) {
                if (this.userProperties == null) {
                    this.userProperties = new LinkedHashMap<>(userProperties);
                } else {
                    this.userProperties.putAll(userProperties);
                }
            }
            return this;
        }

        public Builder messageExpiry(Duration messageExpiry) {
            this.messageExpiry = messageExpiry;
            return this;
        }

        public MqttPublishRecord build() {
            return new MqttPublishRecord(topic, payload, qos, retained, userProperties, messageExpiry);
        }
    }
}
