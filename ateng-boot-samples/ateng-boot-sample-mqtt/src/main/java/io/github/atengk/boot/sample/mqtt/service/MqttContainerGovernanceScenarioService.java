package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.listener.MqttAcknowledgment;
import io.github.atengk.boot.mqtt.listener.MqttContainerManager;
import io.github.atengk.boot.mqtt.listener.MqttEndpointStatus;
import io.github.atengk.boot.mqtt.properties.AckMode;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.dto.MqttContainerSummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景业务服务 6：运行期消费监听容器动态运维、手动确认、退避重试与引用计数削峰流控
 *
 * <p>演示特性：
 * <ul>
 *   <li>端点动态暂停（{@code pause}）与引用计数归零后向 Broker 真实退订</li>
 *   <li>端点动态恢复（{@code resume}）与重建 Broker 订阅</li>
 *   <li>{@link AckMode#MANUAL} 手动确认与显式 {@code nackToDlt()} 转死信</li>
 *   <li>可恢复瞬态异常受管指数退避重试与顺序保护</li>
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
    private final List<String> manualAckedMessages = new CopyOnWriteArrayList<>();
    private final List<String> retriedMessages = new CopyOnWriteArrayList<>();
    private final List<String> retryAttemptTraces = new CopyOnWriteArrayList<>();
    private final List<String> sharedFlowMessages = new CopyOnWriteArrayList<>();
    private final List<String> noLocalMessages = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> retryAttemptsByPayload = new ConcurrentHashMap<>();

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
     * 发送至 MANUAL 手动确认演示通道
     */
    public void publishManualMessage(String payload) {
        log.info("[SCENARIO-GOV] Publishing manual ACK message: {}", payload);
        mqttTemplate.publish("iot/governance/manual-ack", payload, 1);
    }

    /**
     * 发送至瞬态故障重试演示通道
     */
    public void publishRetryMessage(String payload) {
        log.info("[SCENARIO-GOV] Publishing retryable message: {}", payload);
        retryAttemptsByPayload.remove(normalizeRetryKey(payload));
        mqttTemplate.publish("iot/governance/retry-channel", payload != null ? payload : "", 1);
    }

    /**
     * 发送消息至共享流控演示通道 ($share/gov-cluster/iot/governance/shared-flow)
     *
     * @param payload 测试载荷
     */
    public void publishSharedFlowMessage(String payload) {
        mqttTemplate.publish("iot/governance/shared-flow", payload, 1);
    }

    /**
     * 向 noLocal + retainHandling=2 演示通道发送保留消息（用于验证恢复/订阅时不重推历史保留报文）
     *
     * @param payload 保留报文内容
     */
    public void publishNoLocalRetained(String payload) {
        mqttTemplate.publishRetained("iot/governance/nolocal-channel", payload, 1);
    }

    /**
     * 清理 noLocal 演示通道上的 Broker 保留消息
     */
    public void clearNoLocalRetained() {
        mqttTemplate.clearRetained("iot/governance/nolocal-channel", 1);
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

    /**
     * 获取容器运维与可靠性指标汇总视图
     */
    public MqttContainerSummaryVO getSummary() {
        List<String> paused = new ArrayList<>();
        for (MqttEndpointStatus status : containerManager.getEndpointStatuses()) {
            if (status.paused()) {
                paused.add(status.endpointId());
            }
        }
        return MqttContainerSummaryVO.builder()
                .pausedEndpoints(Collections.unmodifiableList(paused))
                .subscribedTopics(containerManager.getSubscribedTopics(null))
                .manualAckedMessages(Collections.unmodifiableList(new ArrayList<>(manualAckedMessages)))
                .retriedMessages(Collections.unmodifiableList(new ArrayList<>(retriedMessages)))
                .retryAttemptTraces(Collections.unmodifiableList(new ArrayList<>(retryAttemptTraces)))
                .sharedFlowMessages(Collections.unmodifiableList(new ArrayList<>(sharedFlowMessages)))
                .build();
    }

    @MqttListener(topics = "iot/governance/channel", qos = 1)
    public void onGovernanceMessage(String message) {
        log.info("[SCENARIO-GOV] Received message on governance channel: {}", message);
        receivedMessages.add(message);
    }

    @MqttListener(topics = "iot/governance/manual-ack", qos = 1, ackMode = AckMode.MANUAL, dltTopic = "iot/governance/manual-ack/DLT")
    public void onManualAckMessage(String payload, MqttAcknowledgment ack) {
        if (payload != null && payload.contains("REJECT_DLT")) {
            log.warn("[SCENARIO-GOV] Explicit nackToDlt requested for manual payload: {}", payload);
            ack.nackToDlt();
            return;
        }
        manualAckedMessages.add(payload);
        ack.ack();
        log.info("[SCENARIO-GOV] Manually acknowledged payload: {}", payload);
    }

    @MqttListener(topics = "iot/governance/retry-channel", qos = 1, concurrency = 1, maxRetries = 2, dltTopic = "iot/governance/retry-channel/DLT")
    public void onRetryableMessage(String payload) {
        int attempt = retryAttemptsByPayload.computeIfAbsent(normalizeRetryKey(payload), k -> new AtomicInteger(0)).incrementAndGet();
        retryAttemptTraces.add("attempt-" + attempt + ":" + payload);
        if (payload != null && payload.startsWith("TRANSIENT:") && attempt == 1) {
            throw new RuntimeException("Simulated transient error on first attempt");
        }
        retriedMessages.add(payload);
        log.info("[SCENARIO-GOV] Processed retryable payload [{}] on attempt {}", payload, attempt);
    }

    private String normalizeRetryKey(String payload) {
        return payload != null ? payload : "";
    }

    @MqttListener(topics = "iot/governance/shared-flow", sharedGroup = "gov-cluster", qos = 1)
    public void onSharedFlowPrimary(String payload) {
        sharedFlowMessages.add("primary:" + payload);
    }

    @MqttListener(topics = "iot/governance/shared-flow", sharedGroup = "gov-cluster", qos = 1)
    public void onSharedFlowBackup(String payload) {
        sharedFlowMessages.add("backup:" + payload);
    }

    /**
     * 通过默认主客户端向 noLocal 信道自发测试报文（验证同连接自发回环被过滤）
     *
     * @param payload 测试载荷
     */
    public void publishNoLocalSelfEcho(String payload) {
        mqttTemplate.publish("iot/governance/nolocal-channel", payload, 1);
    }

    @MqttListener(topics = "iot/governance/nolocal-channel", qos = 1, noLocal = true, retainHandling = 2)
    public void onNoLocalMessage(String payload) {
        noLocalMessages.add(payload);
    }

    public List<String> getNoLocalMessages() {
        return Collections.unmodifiableList(new ArrayList<>(noLocalMessages));
    }

    public List<String> getReceivedMessages() {
        return Collections.unmodifiableList(receivedMessages);
    }

    public void clear() {
        receivedMessages.clear();
        manualAckedMessages.clear();
        retriedMessages.clear();
        retryAttemptTraces.clear();
        sharedFlowMessages.clear();
        noLocalMessages.clear();
        retryAttemptsByPayload.clear();
    }
}
