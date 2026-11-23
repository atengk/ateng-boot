package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.template.MqttDeadLetterReplayTemplate;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.model.ValidatedWorkOrder;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景业务服务 5：毒丸死信（DLT）隔离、只读探查与受控重放治理
 *
 * <p>演示特性：
 * <ul>
 *   <li>不可恢复致命异常（{@link IllegalArgumentException}）触发 DLT 毒丸转移</li>
 *   <li>只读探查（{@code peek}）零污染排查死信</li>
 *   <li>受控重放（{@code replay}）至目标恢复主题，内置防二次雪崩熔断</li>
 *   <li>确定性清理（{@code purge}）清除死信主题积压</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class DeadLetterReplayScenarioService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayScenarioService.class);

    private final MqttTemplate mqttTemplate;
    private final MqttDeadLetterReplayTemplate deadLetterReplayTemplate;

    private final List<String> normalMessages = new CopyOnWriteArrayList<>();
    private final List<String> recoveredMessages = new CopyOnWriteArrayList<>();

    public DeadLetterReplayScenarioService(MqttTemplate mqttTemplate,
                                           MqttDeadLetterReplayTemplate deadLetterReplayTemplate) {
        this.mqttTemplate = mqttTemplate;
        this.deadLetterReplayTemplate = deadLetterReplayTemplate;
    }

    /**
     * 发送毒丸消息测试 DLT 转移
     */
    public void triggerPoisonPill(String payload) {
        log.info("[SCENARIO-DLT] Triggering payload on poison test topic: {}", payload);
        mqttTemplate.publish("iot/devices/poison-test", payload, 1);
    }

    /**
     * 连续投递多条毒丸报文以验证 MqttDeadLetterStore 顺序留存能力
     *
     * @param payloads 毒丸载荷列表
     */
    public void triggerMultiPoison(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        for (String payload : payloads) {
            triggerPoisonPill(payload);
        }
    }

    /**
     * 投递工单载荷至 @Valid 校验监听主题，模拟非法工单入库前零重试直入死信
     *
     * @param gatewayId  来源网关逻辑标识
     * @param orderNo    工单编号
     * @param targetTemp 目标温度
     */
    public void triggerInvalidWorkOrder(String gatewayId, String orderNo, Integer targetTemp) {
        String effectiveGateway = (gatewayId != null && !gatewayId.isBlank()) ? gatewayId.trim() : "gw-south-01";
        ValidatedWorkOrder order = new ValidatedWorkOrder(
                orderNo,
                targetTemp != null ? targetTemp : 0,
                effectiveGateway
        );
        log.info("[SCENARIO-DLT] Triggering work order payload for @Valid validation check: {}", order);
        mqttTemplate.publish("iot/devices/valid-order", order, 1);
    }

    /**
     * 只读探查指定死信主题的死信报文
     */
    public List<MqttDeadLetterRecord> peekDeadLetters(String dltTopic, int maxRecords, Duration timeout) {
        log.info("[SCENARIO-DLT] Peeking DLT topic [{}] up to [{}] records", dltTopic, maxRecords);
        return deadLetterReplayTemplate.peek(dltTopic, maxRecords, timeout);
    }

    /**
     * 受控重放死信报文至目标恢复主题
     */
    public int replayDeadLetters(String dltTopic, String targetTopic) {
        return replayDeadLetters(dltTopic, targetTopic, 10, Duration.ofSeconds(5));
    }

    /**
     * 受控重放死信报文至目标恢复主题并指定重放上限与超时
     */
    public int replayDeadLetters(String dltTopic, String targetTopic, int maxRecords, Duration timeout) {
        log.info("[SCENARIO-DLT] Replaying DLT topic [{}] to target [{}] (maxRecords={}, timeout={})",
                dltTopic, targetTopic, maxRecords, timeout);
        return deadLetterReplayTemplate.replay(dltTopic, targetTopic, maxRecords, null, timeout);
    }

    /**
     * 清理指定死信主题的积压报文
     */
    public void purgeDeadLetters(String dltTopic) {
        log.info("[SCENARIO-DLT] Purging DLT topic [{}]", dltTopic);
        deadLetterReplayTemplate.purge(dltTopic);
    }

    /**
     * 毒丸测试监听端点：遇到 POISON_ 前缀载荷抛出不可恢复致命异常
     */
    @MqttListener(topics = "iot/devices/poison-test", qos = 1)
    public void onPoisonPillMessage(String payload) {
        if (payload != null && payload.startsWith("POISON_")) {
            log.warn("[SCENARIO-DLT] Fatal poison pill detected! Throwing IllegalArgumentException to trigger DLT");
            throw new IllegalArgumentException("FATAL_POISON_PILL");
        }
        log.info("[SCENARIO-DLT] Normal payload accepted: {}", payload);
        normalMessages.add(payload);
    }

    /**
     * @Valid 消费端参数校验演示端点：非法工单 0 次重试直入 iot/devices/valid-order/DLT
     */
    @MqttListener(topics = "iot/devices/valid-order", qos = 1, maxRetries = 3, dltTopic = "iot/devices/valid-order/DLT")
    public void onValidWorkOrder(@Valid ValidatedWorkOrder workOrder) {
        log.info("[SCENARIO-DLT] Accepted valid work order: {}", workOrder);
        normalMessages.add(workOrder.getOrderNo());
    }

    /**
     * 恢复主题监听端点：接收受控重放后的报文
     */
    @MqttListener(topics = "iot/devices/poison-recovered", qos = 1)
    public void onRecoveredMessage(String payload) {
        log.info("[SCENARIO-DLT] Received recovered message: {}", payload);
        recoveredMessages.add(payload);
    }

    public List<String> getNormalMessages() {
        return Collections.unmodifiableList(normalMessages);
    }

    public List<String> getRecoveredMessages() {
        return Collections.unmodifiableList(recoveredMessages);
    }

    public void clear() {
        normalMessages.clear();
        recoveredMessages.clear();
    }
}
