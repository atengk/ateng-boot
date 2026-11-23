package io.github.atengk.boot.mqtt.event;

import org.springframework.context.ApplicationEvent;
import org.springframework.util.Assert;

/**
 * MQTT 客户端连接生命周期领域事件基类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public abstract class MqttConnectionEvent extends ApplicationEvent {

    private final String clientName;
    private final String serverUri;
    private final String clientId;

    protected MqttConnectionEvent(Object source, String clientName, String serverUri, String clientId) {
        super(source);
        Assert.hasText(clientName, "clientName must not be blank");
        this.clientName = clientName;
        this.serverUri = serverUri;
        this.clientId = clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public String getServerUri() {
        return serverUri;
    }

    public String getClientId() {
        return clientId;
    }
}
