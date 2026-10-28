package io.github.atengk.boot.mqtt.codec;

import io.github.atengk.boot.core.exception.BusinessException;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 复合 MQTT 消息编解码转换器
 * 编排管理多个 MqttMessageConverter SPI，按序自适应匹配最合适的目标类型转译器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class CompositeMqttMessageConverter implements MqttMessageConverter {

    private final List<MqttMessageConverter> converters = new CopyOnWriteArrayList<>();

    public CompositeMqttMessageConverter() {
        this(Collections.emptyList());
    }

    public CompositeMqttMessageConverter(List<MqttMessageConverter> customConverters) {
        // 1. 注册自定义转换器（高优先级）
        if (customConverters != null) {
            converters.addAll(customConverters);
        }
        // 2. 注册默认内置转换器
        converters.add(new ByteArrayMqttMessageConverter());
        converters.add(new StringMqttMessageConverter());
        converters.add(new HexMqttMessageConverter());
        converters.add(new JsonMqttMessageConverter());
    }

    public void addConverter(MqttMessageConverter converter) {
        if (converter != null) {
            converters.add(0, converter);
        }
    }

    public List<MqttMessageConverter> getConverters() {
        return Collections.unmodifiableList(converters);
    }

    @Override
    public boolean supports(Class<?> targetType) {
        for (MqttMessageConverter converter : converters) {
            if (converter.supports(targetType)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object fromMessage(MqttMessage message, Class<?> targetType) {
        for (MqttMessageConverter converter : converters) {
            if (converter.supports(targetType)) {
                return converter.fromMessage(message, targetType);
            }
        }
        throw new BusinessException("No suitable MqttMessageConverter found for target type: " + targetType.getName());
    }

    @Override
    public byte[] toPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        for (MqttMessageConverter converter : converters) {
            if (converter.supports(payload.getClass())) {
                return converter.toPayload(payload);
            }
        }
        throw new BusinessException("No suitable MqttMessageConverter found to serialize payload of type: " + payload.getClass().getName());
    }
}
