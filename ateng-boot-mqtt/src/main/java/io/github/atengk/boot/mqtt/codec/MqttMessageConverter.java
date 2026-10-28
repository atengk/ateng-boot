package io.github.atengk.boot.mqtt.codec;

import org.eclipse.paho.mqttv5.common.MqttMessage;

/**
 * MQTT 消息编解码转换器 SPI
 * 负责原始协议载荷（byte[]）与业务强类型参数对象双向转译
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface MqttMessageConverter {

    /**
     * 判断当前转换器是否支持将报文转换为指定的目标类型
     *
     * @param targetType 目标类型 Class
     * @return true 若支持
     */
    boolean supports(Class<?> targetType);

    /**
     * 将入站原始报文反序列化/转译为目标业务对象
     *
     * @param message    Paho 原生 MQTT 报文
     * @param targetType 目标类型 Class
     * @return 转换后的目标业务对象
     */
    Object fromMessage(MqttMessage message, Class<?> targetType);

    /**
     * 将出站业务实体对象序列化/转译为字节数组
     *
     * @param payload 业务载荷对象
     * @return 转译后的字节数组
     */
    byte[] toPayload(Object payload);
}
