package io.github.atengk.boot.mqtt.handler;

/**
 * MQTT 物理发信失败终极补偿处理器扩展点 (SPI)
 * <p>
 * 当发信过程中发生网络异常、事务提交后发信失败或底层 Broker 拒绝时触发，
 * 业务系统可据此实现发信失败记录持久化入库、告警通知或外部补偿队列投递。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@FunctionalInterface
public interface MqttSendFailureHandler {

    /**
     * 发信最终失败时被框架回调
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    发送载荷原始字节
     * @param qos        QoS 等级
     * @param retained   是否保留消息
     * @param cause      捕获的原始失败异常
     */
    void onFailure(String clientName, String topic, byte[] payload, int qos, boolean retained, Throwable cause);
}
