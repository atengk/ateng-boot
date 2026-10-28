package io.github.atengk.boot.mqtt.codec;

import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.nio.charset.StandardCharsets;

/**
 * 字符串文本 MQTT 消息转换器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class StringMqttMessageConverter implements MqttMessageConverter {

    @Override
    public boolean supports(Class<?> targetType) {
        return CharSequence.class.isAssignableFrom(targetType);
    }

    @Override
    public Object fromMessage(MqttMessage message, Class<?> targetType) {
        if (message == null || message.getPayload() == null) {
            return "";
        }
        return new String(message.getPayload(), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] toPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }
}
