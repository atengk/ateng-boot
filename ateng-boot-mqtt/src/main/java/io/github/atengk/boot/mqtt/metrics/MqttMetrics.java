package io.github.atengk.boot.mqtt.metrics;

import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MQTT Micrometer 云原生黄金可观测性度量指标组件
 * 暴露连接状态 Gauge、出站发信 Counter、入站接收 Counter、消费耗时 Timer 与毒丸死信 DLT Counter
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Gauge> registeredClientGauges = new ConcurrentHashMap<>();
    private final Set<NamedMqttClientRegistry> boundRegistries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ReentrantLock bindLock = new ReentrantLock();

    public MqttMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * 绑定并为所有命名客户端注册连接状态 Gauge 监控，同时挂载动态客户端注册与注销联动钩子
     *
     * @param clientRegistry 命名客户端注册中心
     */
    public void bindClientRegistry(NamedMqttClientRegistry clientRegistry) {
        if (clientRegistry == null) {
            return;
        }
        for (Map.Entry<String, MqttClientHandle> entry : clientRegistry.getClientHandles().entrySet()) {
            bindClientGauge(entry.getKey(), entry.getValue());
        }
        bindLock.lock();
        try {
            if (boundRegistries.add(clientRegistry)) {
                clientRegistry.addClientRegisteredListener(handle -> bindClientGauge(handle.getClientName(), handle));
                clientRegistry.addClientUnregisterListener(this::unbindClient);
            }
        } finally {
            bindLock.unlock();
        }
    }

    /**
     * 为单个命名客户端绑定连接状态 Gauge（若已存在则先解绑旧 Gauge 以防句柄残留）
     *
     * @param clientName 客户端逻辑名称
     * @param handle     客户端句柄
     */
    public void bindClientGauge(String clientName, MqttClientHandle handle) {
        if (clientName == null || handle == null) {
            return;
        }
        unbindClient(clientName);
        Gauge gauge = Gauge.builder("mqtt.client.status", handle, h -> h.isConnected() ? 1.0 : 0.0)
                .tag("client", clientName)
                .description("MQTT client connection status (1=connected, 0=disconnected)")
                .register(meterRegistry);
        registeredClientGauges.put(clientName, gauge);
    }

    /**
     * 解绑并移除指定已注销客户端的连接状态 Gauge，防止指标与内存泄漏
     *
     * @param clientName 客户端逻辑名称
     */
    public void unbindClient(String clientName) {
        if (clientName == null) {
            return;
        }
        Gauge old = registeredClientGauges.remove(clientName);
        if (old != null) {
            meterRegistry.remove(old.getId());
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
        recordPublished(clientName, topic, qos, -1L);
    }

    /**
     * 记录出站消息发送成功与耗时
     *
     * @param clientName    客户端名称
     * @param topic         目标主题
     * @param qos           QoS 等级
     * @param durationNanos 发送耗时（纳秒）
     */
    public void recordPublished(String clientName, String topic, int qos, long durationNanos) {
        Counter.builder("mqtt.message.published")
                .tag("client", clientName != null ? clientName : "unknown")
                .tag("topic", topic != null ? topic : "unknown")
                .tag("qos", String.valueOf(qos))
                .description("Count of published MQTT messages")
                .register(meterRegistry)
                .increment();

        if (durationNanos >= 0) {
            Timer.builder("mqtt.publish.latency")
                    .tag("client", clientName != null ? clientName : "unknown")
                    .tag("topic", topic != null ? topic : "unknown")
                    .tag("qos", String.valueOf(qos))
                    .description("MQTT message publishing latency")
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }
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

    /**
     * 记录请求-响应 (Request-Reply) 超时计数
     *
     * @param clientName 客户端名称
     * @param topic      请求主题
     */
    public void recordRequestTimeout(String clientName, String topic) {
        Counter.builder("mqtt.requestreply.timeout")
                .tag("client", clientName != null ? clientName : "unknown")
                .tag("topic", topic != null ? topic : "unknown")
                .description("Count of timed out MQTT request-reply messages")
                .register(meterRegistry)
                .increment();
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
