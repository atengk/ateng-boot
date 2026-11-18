package io.github.atengk.boot.mqtt.listener;

/**
 * MQTT {@link io.github.atengk.boot.mqtt.properties.AckMode#MANUAL} 手动确认模式控制句柄
 * <p>
 * 保证严格单次幂等语义：{@link #ack()}、{@link #nackToDlt()}、{@link #abandonWithoutAck()}
 * 任意一个状态跃迁完成后，其余及后续重复调用均为幂等空操作。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
public interface MqttAcknowledgment {

    /**
     * 显式提交正向确认（仅对 QoS 1/2 向 Broker 发送 PUBACK/PUBCOMP 释放飞行窗口）
     */
    void ack();

    /**
     * 显式拒绝当前消息并立即路由至死信主题（或死信存储）后提交确认阻断重推
     */
    void nackToDlt();

    /**
     * 显式放弃本次消费且不向 Broker 提交确认（等待连接断开或后续由 Broker 重投）
     */
    void abandonWithoutAck();

    /**
     * 判断当前消息是否已完成正向确认或死信转移后确认
     *
     * @return true 若已提交确认（调用 {@link #abandonWithoutAck()} 时保持 false）
     */
    boolean isAcknowledged();
}
