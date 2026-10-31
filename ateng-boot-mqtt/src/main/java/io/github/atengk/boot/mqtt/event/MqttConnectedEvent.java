package io.github.atengk.boot.mqtt.event;

/**
 * MQTT 客户端连接成功领域事件（包含初次连接与断线自愈重连）
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttConnectedEvent extends MqttConnectionEvent {

    private final boolean reconnect;

    public MqttConnectedEvent(Object source, String clientName, String serverUri, String clientId, boolean reconnect) {
        super(source, clientName, serverUri, clientId);
        this.reconnect = reconnect;
    }

    /**
     * 是否为断网自愈重连
     *
     * @return true 为重连恢复，false 为初次建立连接
     */
    public boolean isReconnect() {
        return reconnect;
    }

    @Override
    public String toString() {
        return "MqttConnectedEvent{" +
                "clientName='" + getClientName() + '\'' +
                ", serverUri='" + getServerUri() + '\'' +
                ", clientId='" + getClientId() + '\'' +
                ", reconnect=" + reconnect +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
