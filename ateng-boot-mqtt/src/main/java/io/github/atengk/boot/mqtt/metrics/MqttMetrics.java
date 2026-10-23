package io.github.atengk.boot.mqtt.metrics;

import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MQTT Micrometer 云原生黄金可观测性度量指标组件
 * 暴露连接状态 Gauge、出站发信 Counter、入站接收 Counter、消费耗时 Timer 与毒丸死信 DLT Counter
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Boolean> registeredClients = new ConcurrentHashMap<>();

    public MqttMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * 绑定并为所有命名客户端注册连接状态 Gauge 监控
     *
     * @param clientRegistry 命名客户端注册中心
     */
    public void bindClientRegistry(NamedMqttClientRegistry clientRegistry) {
        if (clientRegistry == null) {
            return;
        }
        for (Map.Entry<String, MqttClientHandle> entry : clientRegistry.getClientHandles().entrySet()) {
            String clientName = entry.getKey();
            MqttClientHandle handle = entry.getValue();
            if (registeredClients.putIfAbsent(clientName, Boolean.TRUE) == null) {
                Gauge.builder("mqtt.client.status", handle, h -> h.isConnected() ? 1.0 : 0.0)
                        .tag("client", clientName)
                        .description("MQTT client connection status (1=connected, 0=disconnected)")
                        .register(meterRegistry);
            }
        }
    }

    /**
     * 记录出站消息发送成功
     *
     * @param clientName 客户端名称
     * @param topic      目标主题
     * @param qos        QoS 等级
     */
    public void recordPublished(String clientName, String topic, int qos) {
        Counter.builder("mqtt.message.published")
                .tag("client", clientName != null ? clientName : "unknown")
                .tag("topic", topic != null ? topic : "unknown")
                .tag("qos", String.valueOf(qos))
                .description("Count of published MQTT messages")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录入站消息接收成功
     *
     * @param clientName 客户端名称
     * @param topic      来源主题
     */
    public void recordReceived(String clientName, String topic) {
        Counter.builder("mqtt.message.received")
                .tag("client", clientName != null ? clientName : "unknown")
                .tag("topic", topic != null ? topic : "unknown")
                .description("Count of received MQTT messages")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录监听器端点消费耗时
     *
     * @param endpointId    端点标识
     * @param topic         实际主题
     * @param success       消费是否成功
     * @param durationNanos 耗时（纳秒）
     */
    public void recordConsume(String endpointId, String topic, boolean success, long durationNanos) {
        Timer.builder("mqtt.consumer.latency")
                .tag("endpoint", endpointId != null ? endpointId : "unknown")
                .tag("topic", topic != null ? topic : "unknown")
                .tag("status", success ? "success" : "failure")
                .description("MQTT message consumption latency")
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录毒丸死信 DLT 转移计数
     *
     * @param endpointId 端点标识
     * @param topic      原主题
     */
    public void recordDlt(String endpointId, String topic) {
        Counter.builder("mqtt.consumer.dlt")
                .tag("endpoint", endpointId != null ? endpointId : "unknown")
                .tag("topic", topic != null ? topic : "unknown")
                .description("Count of poison pill messages routed to DLT")
                .register(meterRegistry)
                .increment();
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
