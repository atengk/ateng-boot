package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 场景业务服务 1：设备遥测上行、主题通配提取、4D 上下文透传与集群共享订阅
 *
 * <p>演示特性：
 * <ul>
 *   <li>主题通配符变量提取：{@code iot/devices/{deviceId}/telemetry} 自动强类型绑定</li>
 *   <li>MQTT 5.0 标头与四维上下文（TraceId、TenantId、UserId）透传与沙箱还原</li>
 *   <li>全量标头 Map 字典无缝注入（{@code @MqttHeader Map<String, Object>}）</li>
 *   <li>集群共享订阅（{@code $share/telemetry-worker/...}）多实例无锁均衡轮询</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class DeviceTelemetryScenarioService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTelemetryScenarioService.class);

    private final MqttTemplate mqttTemplate;

    private final List<DeviceTelemetry> receivedTelemetries = new CopyOnWriteArrayList<>();
    private final Map<String, String> receivedTopics = new ConcurrentHashMap<>();
    private final Map<String, String> receivedVariables = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> receivedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastTraceId = new AtomicReference<>();
    private final AtomicReference<String> lastTenantId = new AtomicReference<>();
    private final AtomicReference<String> lastUserId = new AtomicReference<>();

    private final List<String> receivedClusterMetrics = new CopyOnWriteArrayList<>();

    public DeviceTelemetryScenarioService(MqttTemplate mqttTemplate) {
        this.mqttTemplate = mqttTemplate;
    }

    /**
     * 发布设备遥测数据
     */
    public void publishTelemetry(String deviceId, double temp, double humidity) {
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        mqttTemplate.publish("iot/devices/" + deviceId + "/telemetry", telemetry, 1);
        log.info("[SCENARIO-TELEMETRY] Published telemetry for device [{}]: temp={}, humidity={}",
                deviceId, temp, humidity);
    }

    /**
     * 发布带有自定义标头的设备遥测数据
     */
    public void publishTelemetryWithHeaders(String deviceId, double temp, double humidity, Map<String, String> headers) {
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        mqttTemplate.publish("iot/devices/" + deviceId + "/telemetry", telemetry, 1, false, headers);
        log.info("[SCENARIO-TELEMETRY] Published telemetry with headers for device [{}]: headers={}",
                deviceId, headers);
    }

    /**
     * 发布集群度量数据至共享订阅主题
     */
    public void publishClusterMetric(String metricJson) {
        mqttTemplate.publish("iot/cluster/metrics", metricJson, 1);
        log.info("[SCENARIO-TELEMETRY] Published cluster metric: {}", metricJson);
    }

    /**
     * 1. 声明式设备遥测上行监听器
     */
    @MqttListener(topics = "iot/devices/{deviceId}/telemetry", qos = 1)
    public void onDeviceTelemetry(DeviceTelemetry telemetry,
                                  @MqttTopic String topic,
                                  @MqttVariable("deviceId") String deviceId,
                                  @MqttHeader(value = "x-trace-id", required = false) String traceId,
                                  @MqttHeader Map<String, Object> allHeaders) {
        log.info("[SCENARIO-TELEMETRY] Received telemetry: deviceId={}, topic={}, traceId={}, allHeaders={}",
                deviceId, topic, traceId, allHeaders);

        receivedTelemetries.add(telemetry);
        receivedTopics.put(deviceId, topic);
        receivedVariables.put(deviceId, deviceId);
        if (traceId != null) {
            lastTraceId.set(traceId);
        }
        if (allHeaders != null) {
            receivedHeaders.put(deviceId, allHeaders);
        }

        // 还原受管 4D 上下文（由 MessageContextPropagator 自动透传）
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null) {
            lastTenantId.set(tenantId);
        }
        String userId = UserContextHolder.getUserId();
        if (userId != null) {
            lastUserId.set(userId);
        }
    }

    /**
     * 2. 集群共享订阅监听器
     */
    @MqttListener(topics = "iot/cluster/metrics", sharedGroup = "telemetry-worker", qos = 1)
    public void onClusterMetric(String metricJson) {
        log.info("[SCENARIO-TELEMETRY] Cluster worker consumed shared metric: {}", metricJson);
        receivedClusterMetrics.add(metricJson);
    }

    public List<DeviceTelemetry> getReceivedTelemetries() {
        return Collections.unmodifiableList(receivedTelemetries);
    }

    public String getReceivedTopic(String deviceId) {
        return receivedTopics.get(deviceId);
    }

    public String getReceivedVariable(String deviceId) {
        return receivedVariables.get(deviceId);
    }

    public Map<String, Object> getReceivedHeaders(String deviceId) {
        return receivedHeaders.get(deviceId);
    }

    public String getLastTraceId() {
        return lastTraceId.get();
    }

    public String getLastTenantId() {
        return lastTenantId.get();
    }

    public String getLastUserId() {
        return lastUserId.get();
    }

    public List<String> getReceivedClusterMetrics() {
        return Collections.unmodifiableList(receivedClusterMetrics);
    }

    public void clear() {
        receivedTelemetries.clear();
        receivedTopics.clear();
        receivedVariables.clear();
        receivedHeaders.clear();
        lastTraceId.set(null);
        lastTenantId.set(null);
        lastUserId.set(null);
        receivedClusterMetrics.clear();
    }
}
