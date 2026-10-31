package io.github.atengk.boot.mqtt.interceptor;

import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.util.Map;

/**
 * MQTT 消息切面拦截器 SPI
 * 贯穿消息发布与消费全生命周期的扩展拦截切面，支持审计、动态验签与标头注入、数据脱敏及全链路耗时监控
 *
 * @author Ateng
 * @since 2026-10-31
 */
public interface MqttMessageInterceptor {

    /**
     * 消息发布前置拦截切面
     *
     * @param clientName     客户端逻辑名称
     * @param topic          目标发布主题
     * @param payload        待发布消息载荷字节数组
     * @param qos            QoS 等级 (0, 1, 2)
     * @param retained       是否保留消息
     * @param userProperties 自定义标头字典（可直接追加/修改标头以实现动态签名）
     * @return true 允许继续发布，false 拦截终止发布
     */
    default boolean prePublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties) {
        return true;
    }

    /**
     * 消息发布后置拦截切面
     *
     * @param clientName     客户端逻辑名称
     * @param topic          目标发布主题
     * @param payload        已发布消息载荷字节数组
     * @param qos            QoS 等级 (0, 1, 2)
     * @param retained       是否保留消息
     * @param userProperties 自定义标头字典
     * @param ex             发布异常（发布成功时为 null）
     */
    default void postPublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties, Throwable ex) {}

    /**
     * 消息消费前置拦截切面
     *
     * @param clientName 客户端逻辑名称
     * @param endpointId 消费端点 ID（未指定时由框架自增生成）
     * @param topic      实际接收的主题
     * @param message    MQTT 原始入站报文
     * @param headers    已提取的用户属性标头字典
     * @return true 允许继续分发至消费方法，false 拦截丢弃当前报文
     */
    default boolean preConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers) {
        return true;
    }

    /**
     * 消息消费后置拦截切面
     *
     * @param clientName 客户端逻辑名称
     * @param endpointId 消费端点 ID
     * @param topic      实际接收的主题
     * @param message    MQTT 原始入站报文
     * @param headers    已提取的用户属性标头字典
     * @param ex         消费方法执行异常（成功时为 null）
     */
    default void postConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers, Throwable ex) {}
}
