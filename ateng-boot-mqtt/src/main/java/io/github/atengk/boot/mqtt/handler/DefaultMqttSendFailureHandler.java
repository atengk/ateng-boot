package io.github.atengk.boot.mqtt.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认 MQTT 发信失败处理器实现
 * 仅记录 WARN 日志，为业务系统未显式注入自定义补偿处理器时提供安全兜底
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DefaultMqttSendFailureHandler implements MqttSendFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultMqttSendFailureHandler.class);

    @Override
    public void onFailure(String clientName, String topic, byte[] payload, int qos, boolean retained, Throwable cause) {
        int payloadLength = payload != null ? payload.length : 0;
        log.warn("[MQTT] Publish failed on client [{}] to topic [{}], payloadLength=[{} bytes], qos=[{}], retained=[{}]: {}",
                clientName, topic, payloadLength, qos, retained, cause.getMessage(), cause);
    }
}
