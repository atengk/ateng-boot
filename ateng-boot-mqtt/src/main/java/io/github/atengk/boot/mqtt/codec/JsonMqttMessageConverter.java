package io.github.atengk.boot.mqtt.codec;

import io.github.atengk.boot.core.util.JsonUtil;
import org.eclipse.paho.mqttv5.common.MqttMessage;

/**
 * 纯原生安全 JSON MQTT 消息转换器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class JsonMqttMessageConverter implements MqttMessageConverter {

    @Override
    public boolean supports(Class<?> targetType) {
        // 作为通用对象转译转换器
        return targetType != null && targetType != byte[].class && !CharSequence.class.isAssignableFrom(targetType);
    }

    @Override
    public Object fromMessage(MqttMessage message, Class<?> targetType) {
        if (message == null || message.getPayload() == null || message.getPayload().length == 0) {
            return null;
        }
        return JsonUtil.parseObject(message.getPayload(), targetType);
    }

    @Override
    public byte[] toPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        return JsonUtil.toJsonBytes(payload);
    }
}
