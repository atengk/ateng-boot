package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.codec.HexPayload;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景业务服务 3：工业国标与车联网十六进制（HexPayload）双向转译
 *
 * <p>演示特性：
 * <ul>
 *   <li>强类型承载十六进制报文（{@link HexPayload}）</li>
 *   <li>{@link io.github.atengk.boot.mqtt.codec.HexMqttMessageConverter} 双向无损转译</li>
 *   <li>自动大小写规范化与自动偶数位前导零补齐</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class IndustrialHexScenarioService {

    private static final Logger log = LoggerFactory.getLogger(IndustrialHexScenarioService.class);

    private final MqttTemplate mqttTemplate;

    private final List<HexPayload> receivedHexPayloads = new CopyOnWriteArrayList<>();
    private final List<String> receivedHexDeviceIds = new CopyOnWriteArrayList<>();

    public IndustrialHexScenarioService(MqttTemplate mqttTemplate) {
        this.mqttTemplate = mqttTemplate;
    }

    /**
     * 发布国标十六进制报文
     */
    public void publishHex(String deviceId, String hexString) {
        HexPayload payload = HexPayload.of(hexString);
        mqttTemplate.publish("iot/devices/" + deviceId + "/hex", payload, 1);
        log.info("[SCENARIO-HEX] Published Hex payload for device [{}]: hex={}", deviceId, hexString);
    }

    /**
     * 工业国标十六进制报文监听器
     */
    @MqttListener(topics = "iot/devices/{deviceId}/hex", qos = 1)
    public void onHexMessage(HexPayload payload,
                             @MqttTopic String topic,
                             @MqttVariable("deviceId") String deviceId) {
        log.info("[SCENARIO-HEX] Received Hex message from device [{}] on topic [{}]: hex={}, bytesLen={}",
                deviceId, topic, payload.getHexString(), payload.getBytes().length);
        receivedHexPayloads.add(payload);
        receivedHexDeviceIds.add(deviceId);
    }

    public List<HexPayload> getReceivedHexPayloads() {
        return Collections.unmodifiableList(receivedHexPayloads);
    }

    public List<String> getReceivedHexDeviceIds() {
        return Collections.unmodifiableList(receivedHexDeviceIds);
    }

    public void clear() {
        receivedHexPayloads.clear();
        receivedHexDeviceIds.clear();
    }
}
