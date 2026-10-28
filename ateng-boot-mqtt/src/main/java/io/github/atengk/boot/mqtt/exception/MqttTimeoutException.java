package io.github.atengk.boot.mqtt.exception;

import io.github.atengk.boot.core.exception.BusinessException;

import java.time.Duration;

/**
 * MQTT 请求-响应模式交互超时异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttTimeoutException extends BusinessException {

    private final String topic;
    private final String correlationId;
    private final Duration timeout;

    public MqttTimeoutException(String message, String topic, String correlationId) {
        this(message, topic, correlationId, null, null);
    }

    public MqttTimeoutException(String message, String topic, String correlationId, Duration timeout) {
        this(message, topic, correlationId, timeout, null);
    }

    public MqttTimeoutException(String message, String topic, String correlationId, Duration timeout, Throwable cause) {
        super(message, cause);
        this.topic = topic;
        this.correlationId = correlationId;
        this.timeout = timeout;
    }

    public String getTopic() {
        return topic;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Duration getTimeout() {
        return timeout;
    }
}
