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
        byte[] payload = message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
        if (targetType == Byte[].class) {
            Byte[] boxed = new Byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                boxed[i] = payload[i];
            }
            return boxed;
        }
        return payload;
    }

    @Override
    public byte[] toPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof Byte[] boxed) {
            byte[] unboxed = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                unboxed[i] = boxed[i] != null ? boxed[i] : (byte) 0;
            }
            return unboxed;
        }
        return new byte[0];
    }
}
