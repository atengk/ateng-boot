package io.github.atengk.boot.mqtt.util;

import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 协议标头与 UserProperties 提取转换工具类
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class MqttHeaderUtils {

    private MqttHeaderUtils() {}

    /**
     * 从 {@link MqttMessage} 中提取用户属性并转换为字符串标头映射
     *
     * @param message MQTT 报文
     * @return 标头键值对映射
     */
    public static Map<String, String> extractHeaders(MqttMessage message) {
        return message != null ? extractHeaders(message.getProperties()) : Collections.emptyMap();
    }

    /**
     * 从 {@link MqttProperties} 中提取用户属性并转换为字符串标头映射
     *
     * @param properties MQTT 5.0 属性包
     * @return 标头键值对映射
     */
    public static Map<String, String> extractHeaders(MqttProperties properties) {
        if (properties == null) {
            return Collections.emptyMap();
        }
        List<UserProperty> userProperties = properties.getUserProperties();
        if (userProperties == null || userProperties.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>(userProperties.size());
        for (UserProperty prop : userProperties) {
            if (prop != null && prop.getKey() != null) {
                headers.put(prop.getKey(), prop.getValue());
            }
        }
        return headers;
    }
}
