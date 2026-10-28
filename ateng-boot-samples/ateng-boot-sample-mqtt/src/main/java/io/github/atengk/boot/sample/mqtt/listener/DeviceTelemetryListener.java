package io.github.atengk.boot.sample.mqtt.listener;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.codec.HexPayload;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.sample.mqtt.model.CommandResponse;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 声明式 MQTT 业务监听器组件
 * 演示通配符动态提取、共享订阅分组均衡、国标十六进制报文转译及 MQTT 5.0 RPC 服务端响应
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Component
public class DeviceTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceTelemetryListener.class);

    private final NamedMqttClientRegistry clientRegistry;

    private final List<DeviceTelemetry> receivedTelemetries = new CopyOnWriteArrayList<>();
    private final Map<String, String> telemetryTopics = new ConcurrentHashMap<>();
    private final Map<String, String> telemetryVariables = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastTraceId = new AtomicReference<>();

    private final List<String> receivedClusterMetrics = new CopyOnWriteArrayList<>();
    private final List<HexPayload> receivedHexPayloads = new CopyOnWriteArrayList<>();
    private final List<String> receivedHexDeviceIds = new CopyOnWriteArrayList<>();

    public DeviceTelemetryListener(NamedMqttClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    /**
     * 1. 设备遥测上行声明式监听器
     * 演示主题通配符变量提取、POJO 自适应反序列化与 User Properties 上下文标头注入
     */
    @MqttListener(topics = "iot/devices/{deviceId}/telemetry", qos = 1)
    public void onDeviceTelemetry(DeviceTelemetry telemetry,
                                  @MqttTopic String topic,
                                  @MqttVariable("deviceId") String deviceId,
                                  @MqttHeader("x-trace-id") String traceId) {
        log.info("[MQTT-Sample] Received telemetry from device [{}] on topic [{}], traceId [{}]: {}",
                deviceId, topic, traceId, telemetry);
        receivedTelemetries.add(telemetry);
        telemetryTopics.put(deviceId, topic);
        telemetryVariables.put(deviceId, deviceId);
        if (traceId != null) {
            lastTraceId.set(traceId);
        }
    }

    /**
     * 2. 集群共享订阅监听器 ($share/telemetry-worker/...)
     * 演示多节点消费均衡，避免重复消费
     */
    @MqttListener(topics = "iot/cluster/metrics", sharedGroup = "telemetry-worker", qos = 1)
    public void onClusterMetric(String metricJson) {
        log.info("[MQTT-Sample] Worker consumed shared metric: {}", metricJson);
        receivedClusterMetrics.add(metricJson);
    }

    /**
     * 3. 工业国标/车联网十六进制二进制报文监听器
     * 演示 HexPayload 强类型十六进制双向转译
     */
    @MqttListener(topics = "iot/devices/{deviceId}/hex", qos = 1)
    public void onHexMessage(HexPayload payload,
                             @MqttTopic String topic,
                             @MqttVariable("deviceId") String deviceId) {
        log.info("[MQTT-Sample] Received Hex message from device [{}] on topic [{}]: hex={}, bytesLen={}",
                deviceId, topic, payload.getHexString(), payload.getBytes().length);
        receivedHexPayloads.add(payload);
        receivedHexDeviceIds.add(deviceId);
    }

    /**
     * 4. 模拟边缘设备端 RPC 控制应答监听器
     * 演示接收下行请求，提取 responseTopic 与 correlationData 并回传结构化响应
     */
    @MqttListener(topics = "iot/rpc/device/control", qos = 1)
    public void onRpcControl(MqttMessage message) {
        if (message.getProperties() == null) {
            return;
        }
        String responseTopic = message.getProperties().getResponseTopic();
        byte[] correlationData = message.getProperties().getCorrelationData();

        if (responseTopic != null && correlationData != null) {
            log.info("[MQTT-Sample] Edge device received RPC command, responding to [{}]", responseTopic);
            CommandResponse response = new CommandResponse(
                    "cmd-" + System.currentTimeMillis(),
                    true,
                    "ACTION_EXECUTED_SUCCESS",
                    System.currentTimeMillis()
            );

            MqttProperties replyProps = new MqttProperties();
            replyProps.setCorrelationData(correlationData);

            MqttMessage replyMsg = new MqttMessage(JsonUtil.toJsonBytes(response));
            replyMsg.setQos(1);
            replyMsg.setProperties(replyProps);

            try {
                clientRegistry.getDefaultClientHandle().getAsyncClient().publish(responseTopic, replyMsg);
            } catch (Exception e) {
                log.error("[MQTT-Sample] Failed to reply RPC command: {}", e.getMessage(), e);
            }
        }
    }

    public List<DeviceTelemetry> getReceivedTelemetries() {
        return Collections.unmodifiableList(receivedTelemetries);
    }

    public List<String> getReceivedClusterMetrics() {
        return Collections.unmodifiableList(receivedClusterMetrics);
    }

    public List<HexPayload> getReceivedHexPayloads() {
        return Collections.unmodifiableList(receivedHexPayloads);
    }

    public List<String> getReceivedHexDeviceIds() {
        return Collections.unmodifiableList(receivedHexDeviceIds);
    }

    public String getLastTraceId() {
        return lastTraceId.get();
    }

    public void clear() {
        receivedTelemetries.clear();
        telemetryTopics.clear();
        telemetryVariables.clear();
        lastTraceId.set(null);
        receivedClusterMetrics.clear();
        receivedHexPayloads.clear();
        receivedHexDeviceIds.clear();
    }
}
