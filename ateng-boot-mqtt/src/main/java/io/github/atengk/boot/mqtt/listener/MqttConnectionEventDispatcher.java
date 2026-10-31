package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.mqtt.event.MqttConnectedEvent;
import io.github.atengk.boot.mqtt.event.MqttConnectionLostEvent;
import io.github.atengk.boot.mqtt.event.MqttDisconnectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.Collections;
import java.util.List;

/**
 * MQTT 连接生命周期事件分发器
 * 自动监听 Spring 容器广播的 MqttConnectionEvent 领域事件，并有序分发至 MqttConnectionEventListener SPI 实现
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttConnectionEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectionEventDispatcher.class);

    private final List<MqttConnectionEventListener> listeners;

    public MqttConnectionEventDispatcher(List<MqttConnectionEventListener> listeners) {
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    @EventListener
    public void onConnected(MqttConnectedEvent event) {
        for (MqttConnectionEventListener listener : listeners) {
            try {
                listener.onConnected(event);
            } catch (Throwable t) {
                log.error("[MQTT] Error invoking MqttConnectionEventListener [{}] onConnected: {}",
                        listener.getClass().getName(), t.getMessage(), t);
            }
        }
    }

    @EventListener
    public void onDisconnected(MqttDisconnectedEvent event) {
        for (MqttConnectionEventListener listener : listeners) {
            try {
                listener.onDisconnected(event);
            } catch (Throwable t) {
                log.error("[MQTT] Error invoking MqttConnectionEventListener [{}] onDisconnected: {}",
                        listener.getClass().getName(), t.getMessage(), t);
            }
        }
    }

    @EventListener
    public void onConnectionLost(MqttConnectionLostEvent event) {
        for (MqttConnectionEventListener listener : listeners) {
            try {
                listener.onConnectionLost(event);
            } catch (Throwable t) {
                log.error("[MQTT] Error invoking MqttConnectionEventListener [{}] onConnectionLost: {}",
                        listener.getClass().getName(), t.getMessage(), t);
            }
        }
    }

    public List<MqttConnectionEventListener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }
}
