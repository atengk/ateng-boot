package io.github.atengk.boot.mqtt.event;

/**
 * MQTT 客户端意外掉线连接丢失领域事件
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttConnectionLostEvent extends MqttConnectionEvent {

    private final Throwable cause;

    public MqttConnectionLostEvent(Object source, String clientName, String serverUri, String clientId, Throwable cause) {
        super(source, clientName, serverUri, clientId);
        this.cause = cause;
    }

    /**
     * 获取导致掉线的底层异常
     *
     * @return 异常原因
     */
    public Throwable getCause() {
        return cause;
    }

    @Override
    public String toString() {
        return "MqttConnectionLostEvent{" +
                "clientName='" + getClientName() + '\'' +
                ", serverUri='" + getServerUri() + '\'' +
                ", clientId='" + getClientId() + '\'' +
                ", cause=" + (cause != null ? cause.getMessage() : "null") +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
