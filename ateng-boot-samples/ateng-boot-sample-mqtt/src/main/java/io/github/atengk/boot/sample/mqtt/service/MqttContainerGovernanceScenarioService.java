package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.listener.MqttContainerManager;
import io.github.atengk.boot.mqtt.listener.MqttEndpointStatus;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景业务服务 6：运行期消费监听容器动态运维与削峰流控
 *
 * <p>演示特性：
 * <ul>
 *   <li>端点动态暂停（{@code pause}）与静默跳过</li>
 *   <li>端点动态恢复（{@code resume}）恢复正常分发</li>
 *   <li>容器运行时状态探查（{@code getEndpointStatuses}）</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class MqttContainerGovernanceScenarioService {

    private static final Logger log = LoggerFactory.getLogger(MqttContainerGovernanceScenarioService.class);

    private final MqttContainerManager containerManager;
    private final MqttTemplate mqttTemplate;

    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();

    public MqttContainerGovernanceScenarioService(MqttContainerManager containerManager,
                                                  MqttTemplate mqttTemplate) {
        this.containerManager = containerManager;
        this.mqttTemplate = mqttTemplate;
    }

    /**
     * 发送治理测试消息
     */
    public void publishMessage(String message) {
        log.info("[SCENARIO-GOV] Publishing governance message: {}", message);
        mqttTemplate.publish("iot/governance/channel", message, 1);
    }

    /**
     * 暂停指定端点
     */
    public boolean pauseEndpoint(String endpointId) {
        log.info("[SCENARIO-GOV] Pausing endpoint [{}]", endpointId);
        containerManager.pause(endpointId);
        return true;
    }

    /**
     * 恢复指定端点
     */
    public boolean resumeEndpoint(String endpointId) {
        log.info("[SCENARIO-GOV] Resuming endpoint [{}]", endpointId);
        containerManager.resume(endpointId);
        return true;
    }

    /**
     * 判断指定端点是否已暂停
     */
    public boolean isPaused(String endpointId) {
        return containerManager.isPaused(endpointId);
    }

    /**
     * 获取容器全量端点运行状态列表
     */
    public List<MqttEndpointStatus> getEndpointStatuses() {
        return containerManager.getEndpointStatuses();
    }

    @MqttListener(topics = "iot/governance/channel", qos = 1)
    public void onGovernanceMessage(String message) {
        log.info("[SCENARIO-GOV] Received message on governance channel: {}", message);
        receivedMessages.add(message);
    }

    public List<String> getReceivedMessages() {
        return Collections.unmodifiableList(receivedMessages);
    }

    public void clear() {
        receivedMessages.clear();
    }
}
