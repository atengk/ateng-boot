package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.model.MqttPublishRecord;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景业务服务 4：批量发信、并发聚合投递与保留消息对称清理
 *
 * <p>演示特性：
 * <ul>
 *   <li>批量发信契约 {@link MqttPublishRecord} 并发聚合投递</li>
 *   <li>保留消息投递（retained = true）</li>
 *   <li>保留消息对称清理（{@code clearRetained(topic)}）</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class BatchPublishScenarioService {

    private static final Logger log = LoggerFactory.getLogger(BatchPublishScenarioService.class);

    private final MqttTemplate mqttTemplate;

    private final List<String> receivedBatchMessages = new CopyOnWriteArrayList<>();
    private final List<String> receivedStatusMessages = new CopyOnWriteArrayList<>();

    public BatchPublishScenarioService(MqttTemplate mqttTemplate) {
        this.mqttTemplate = mqttTemplate;
    }

    /**
     * 批量并发投递设备报文
     */
    public void publishBatch(List<MqttPublishRecord> records) {
        log.info("[SCENARIO-BATCH] Publishing batch of [{}] records", records.size());
        mqttTemplate.publishBatch(records);
    }

    /**
     * 发布设备在线保留状态
     */
    public void publishRetainedStatus(String deviceId, String status) {
        String topic = "iot/devices/" + deviceId + "/status";
        log.info("[SCENARIO-BATCH] Publishing retained status [{}] on topic [{}]", status, topic);
        mqttTemplate.publish(topic, status, 1, true);
    }

    /**
     * 对称清理设备保留状态（发送 0 字节保留消息）
     */
    public void clearRetainedStatus(String deviceId) {
        String topic = "iot/devices/" + deviceId + "/status";
        log.info("[SCENARIO-BATCH] Clearing retained message on topic [{}]", topic);
        mqttTemplate.clearRetained(topic);
    }

    @MqttListener(topics = "iot/devices/{deviceId}/batch", qos = 1)
    public void onBatchMessage(String payload, @MqttVariable("deviceId") String deviceId) {
        log.info("[SCENARIO-BATCH] Received batch message for device [{}]: {}", deviceId, payload);
        receivedBatchMessages.add(payload);
    }

    @MqttListener(topics = "iot/devices/{deviceId}/status", qos = 1)
    public void onStatusMessage(String payload, @MqttVariable("deviceId") String deviceId) {
        log.info("[SCENARIO-BATCH] Received status message for device [{}]: {}", deviceId, payload);
        receivedStatusMessages.add(payload);
    }

    public List<String> getReceivedBatchMessages() {
        return Collections.unmodifiableList(receivedBatchMessages);
    }

    public List<String> getReceivedStatusMessages() {
        return Collections.unmodifiableList(receivedStatusMessages);
    }

    public void clear() {
        receivedBatchMessages.clear();
        receivedStatusMessages.clear();
    }
}
