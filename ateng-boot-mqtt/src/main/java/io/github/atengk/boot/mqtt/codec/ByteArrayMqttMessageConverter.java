package io.github.atengk.boot.mqtt.codec;

import org.eclipse.paho.mqttv5.common.MqttMessage;

/**
 * 字节数组 MQTT 消息转换器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class ByteArrayMqttMessageConverter implements MqttMessageConverter {

    @Override
    public boolean supports(Class<?> targetType) {
        return targetType == byte[].class || targetType == Byte[].class;
    }

    @Override
    public Object fromMessage(MqttMessage message, Class<?> targetType) {
        return message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
    }

    @Override
    public byte[] toPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        return new byte[0];
    }
}
