package io.github.atengk.boot.mqtt.event;

/**
 * MQTT 客户端优雅断开连接领域事件
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttDisconnectedEvent extends MqttConnectionEvent {

    public MqttDisconnectedEvent(Object source, String clientName, String serverUri, String clientId) {
        super(source, clientName, serverUri, clientId);
    }

    @Override
    public String toString() {
        return "MqttDisconnectedEvent{" +
                "clientName='" + getClientName() + '\'' +
                ", serverUri='" + getServerUri() + '\'' +
                ", clientId='" + getClientId() + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
