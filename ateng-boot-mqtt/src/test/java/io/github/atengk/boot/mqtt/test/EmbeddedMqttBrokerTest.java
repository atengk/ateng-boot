package io.github.atengk.boot.mqtt.test;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddedMqttBroker 离线自洽测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class EmbeddedMqttBrokerTest {

    private EmbeddedMqttBroker broker;

    @BeforeEach
    void setUp() {
        broker = new EmbeddedMqttBroker(0);
        broker.start();
    }

    @AfterEach
    void tearDown() {
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("测试 EmbeddedMqttBroker 启动与 Paho 客户端连接握手与断连")
    void testBrokerConnection() throws Exception {
        assertTrue(broker.getPort() > 0, "Broker 应绑定可用临时端口");
        String serverUri = broker.getServerUri();
        assertTrue(serverUri.startsWith("tcp://127.0.0.1:"));

        MqttAsyncClient client = new MqttAsyncClient(serverUri, "test-client-1", new MemoryPersistence());
        try {
            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setCleanStart(true);
            options.setConnectionTimeout(5);

            client.connect(options).waitForCompletion(5000);
            assertTrue(client.isConnected(), "客户端应成功连接至嵌入式 Broker");

            client.disconnect().waitForCompletion(5000);
            assertFalse(client.isConnected(), "客户端应成功断开连接");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("测试主题通配符匹配算法")
    void testTopicMatch() {
        assertTrue(EmbeddedMqttBroker.isTopicMatch("sensor/+/temperature", "sensor/1/temperature"));
        assertTrue(EmbeddedMqttBroker.isTopicMatch("sensor/#", "sensor/1/temperature"));
        assertTrue(EmbeddedMqttBroker.isTopicMatch("sensor/#", "sensor/1/device/status"));
        assertTrue(EmbeddedMqttBroker.isTopicMatch("sensor/+/+", "sensor/1/temperature"));

        assertFalse(EmbeddedMqttBroker.isTopicMatch("sensor/+/temperature", "sensor/1/humidity"));
        assertFalse(EmbeddedMqttBroker.isTopicMatch("sensor/+/temperature", "sensor/1/2/temperature"));
    }
}
