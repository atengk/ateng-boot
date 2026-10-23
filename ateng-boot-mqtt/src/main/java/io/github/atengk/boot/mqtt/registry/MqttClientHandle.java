package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;

/**
 * 单个受管 MQTT 客户端句柄实体
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttClientHandle {

    private final String clientName;
    private final String actualClientId;
    private final MqttAsyncClient asyncClient;
    private final MqttConnectionOptions connectionOptions;
    private final MqttClientProperties clientProperties;

    public MqttClientHandle(String clientName,
                            String actualClientId,
                            MqttAsyncClient asyncClient,
                            MqttConnectionOptions connectionOptions,
                            MqttClientProperties clientProperties) {
        this.clientName = clientName;
        this.actualClientId = actualClientId;
        this.asyncClient = asyncClient;
        this.connectionOptions = connectionOptions;
        this.clientProperties = clientProperties;
    }

    public String getClientName() {
        return clientName;
    }

    public String getActualClientId() {
        return actualClientId;
    }

    public MqttAsyncClient getAsyncClient() {
        return asyncClient;
    }

    public MqttConnectionOptions getConnectionOptions() {
        return connectionOptions;
    }

    public MqttClientProperties getClientProperties() {
        return clientProperties;
    }

    public boolean isConnected() {
        return asyncClient != null && asyncClient.isConnected();
    }
}
