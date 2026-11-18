package io.github.atengk.boot.mqtt.metrics;

import io.github.atengk.boot.mqtt.properties.MqttMetricsProperties;
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

    /**
     * 当显式开启物理主题标签且不同主题数量超过基数上限时的聚合溢出标签值
     */
    public static final String OVERFLOW_TOPIC_TAG = "__overflow__";

    private final MeterRegistry meterRegistry;
    private final MqttMetricsProperties metricsProperties;
    private final Map<String, Gauge> registeredClientGauges = new ConcurrentHashMap<>();
    private final Set<NamedMqttClientRegistry> boundRegistries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> registeredTopicTags = ConcurrentHashMap.newKeySet();
    private final ReentrantLock bindLock = new ReentrantLock();
    private final ReentrantLock cardinalityLock = new ReentrantLock();

    public MqttMetrics(MeterRegistry meterRegistry) {
        this(meterRegistry, new MqttMetricsProperties());
    }

    public MqttMetrics(MeterRegistry meterRegistry, MqttMetricsProperties metricsProperties) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.metricsProperties = metricsProperties != null ? metricsProperties : new MqttMetricsProperties();
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
        io.micrometer.core.instrument.Tags tags = buildClientTags(
                clientName, topic, "qos", String.valueOf(qos));
        Counter.builder("mqtt.message.published")
                .tags(tags)
                .description("Count of published MQTT messages")
                .register(meterRegistry)
                .increment();

        if (durationNanos >= 0) {
            Timer.builder("mqtt.publish.latency")
                    .tags(tags)
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
                .tags(buildClientTags(clientName, topic))
                .description("Count of received MQTT messages")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录监听器端点消费耗时（使用有限基数声明式主题模板标签）
     *
     * @param endpointId    端点标识
     * @param topicPattern  声明式主题模板（如 {@code devices/{deviceId}/telemetry}）
     * @param success       消费是否成功
     * @param durationNanos 耗时（纳秒）
     */
    public void recordConsume(String endpointId, String topicPattern, boolean success, long durationNanos) {
        Timer.builder("mqtt.consumer.latency")
                .tag("endpoint", endpointId != null ? endpointId : "unknown")
                .tag("topic", topicPattern != null ? topicPattern : "unknown")
                .tag("status", success ? "success" : "failure")
                .description("MQTT message consumption latency")
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录毒丸死信 DLT 转移计数（使用有限基数声明式主题模板标签）
     *
     * @param endpointId   端点标识
     * @param topicPattern 声明式主题模板
     */
    public void recordDlt(String endpointId, String topicPattern) {
        Counter.builder("mqtt.consumer.dlt")
                .tag("endpoint", endpointId != null ? endpointId : "unknown")
                .tag("topic", topicPattern != null ? topicPattern : "unknown")
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
                .tags(buildClientTags(clientName, topic))
                .description("Count of timed out MQTT request-reply messages")
                .register(meterRegistry)
                .increment();
    }

    private io.micrometer.core.instrument.Tags buildClientTags(String clientName, String topic, String... extraKeyValues) {
        io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags.of(
                "client", clientName != null ? clientName : "unknown");
        if (extraKeyValues != null && extraKeyValues.length > 0) {
            tags = tags.and(extraKeyValues);
        }
        String boundedTopic = resolveTopicTagIfEnabled(topic);
        if (boundedTopic != null) {
            tags = tags.and("topic", boundedTopic);
        }
        return tags;
    }

    private String resolveTopicTagIfEnabled(String rawTopic) {
        if (!metricsProperties.isIncludeTopicTag()) {
            return null;
        }
        String normalized = rawTopic != null ? rawTopic : "unknown";
        if (registeredTopicTags.contains(normalized)) {
            return normalized;
        }
        int maxCardinality = metricsProperties.getMaxTopicCardinality();
        if (maxCardinality <= 0) {
            return OVERFLOW_TOPIC_TAG;
        }
        cardinalityLock.lock();
        try {
            if (registeredTopicTags.contains(normalized)) {
                return normalized;
            }
            if (registeredTopicTags.size() < maxCardinality) {
                registeredTopicTags.add(normalized);
                return normalized;
            }
            return OVERFLOW_TOPIC_TAG;
        } finally {
            cardinalityLock.unlock();
        }
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
