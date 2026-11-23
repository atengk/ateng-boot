package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.mqtt.event.MqttConnectedEvent;
import io.github.atengk.boot.mqtt.event.MqttConnectionLostEvent;
import io.github.atengk.boot.mqtt.event.MqttDisconnectedEvent;

/**
 * MQTT 客户端连接生命周期监听扩展点 SPI
 * 支持第三方业务 Bean 实现本接口无侵入感知连接建立、优雅断开与异常掉线
 *
 * @author Ateng
 * @since 2026-10-31
 */
public interface MqttConnectionEventListener {

    /**
     * 当客户端连接成功或重连恢复时触发
     *
     * @param event 连接成功事件
     */
    default void onConnected(MqttConnectedEvent event) {}

    /**
     * 当客户端优雅断开连接时触发
     *
     * @param event 断开连接事件
     */
    default void onDisconnected(MqttDisconnectedEvent event) {}

    /**
     * 当客户端因网络波动等异常意外掉线时触发
     *
     * @param event 掉线事件
     */
    default void onConnectionLost(MqttConnectionLostEvent event) {}
}
