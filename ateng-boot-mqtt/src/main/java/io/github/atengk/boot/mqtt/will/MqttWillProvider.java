package io.github.atengk.boot.mqtt.will;

import io.github.atengk.boot.mqtt.properties.MqttClientProperties;

/**
 * 动态 MQTT 客户端遗嘱消息供给器 SPI
 * 允许业务方在客户端连接初始化时动态注入携带宿主 IP、容器 Pod 标识及实例身份的定制化告警报文
 *
 * @author Ateng
 * @since 2026-10-23
 */
@FunctionalInterface
public interface MqttWillProvider {

    /**
     * 获取指定客户端的动态遗嘱消息定义
     *
     * @param clientName     客户端逻辑名称
     * @param actualClientId 实际客户端唯一标识
     * @param clientProps    客户端配置属性
     * @return 遗嘱消息定义（包含目标主题与载荷，若返回 null 则降级使用静态配置）
     */
    MqttWill getWill(String clientName, String actualClientId, MqttClientProperties clientProps);
}
