package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttReply;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.model.CommandResponse;
import io.github.atengk.boot.sample.mqtt.model.DeviceCommand;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 场景业务服务 2：MQTT 5.0 同步与异步 RPC 请求-响应下行控制
 *
 * <p>演示特性：
 * <ul>
 *   <li>边缘端监听 {@code ResponseTopic} 与 {@code CorrelationData} 并回传响应</li>
 *   <li>主控端同步阻塞调用 {@code mqttTemplate.sendAndReceive}</li>
 *   <li>主控端非阻塞异步调用 {@code mqttTemplate.sendAndReceiveAsync}</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class DeviceControlRpcScenarioService {

    private static final Logger log = LoggerFactory.getLogger(DeviceControlRpcScenarioService.class);

    private final MqttTemplate mqttTemplate;
    private final NamedMqttClientRegistry clientRegistry;

    public DeviceControlRpcScenarioService(MqttTemplate mqttTemplate,
                                           NamedMqttClientRegistry clientRegistry) {
        this.mqttTemplate = mqttTemplate;
        this.clientRegistry = clientRegistry;
    }

    /**
     * 发起同步 RPC 设备控制指令
     */
    public CommandResponse sendControlSync(String action, int speed, Duration timeout) {
        DeviceCommand command = new DeviceCommand("cmd-sync-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        log.info("[SCENARIO-RPC] Sending sync RPC command: action={}, speed={}, timeout={}", action, speed, timeout);
        return mqttTemplate.sendAndReceive(
                "iot/rpc/device/control",
                command,
                CommandResponse.class,
                timeout
        );
    }

    /**
     * 发起异步非阻塞 RPC 设备控制指令
     */
    public CompletableFuture<CommandResponse> sendControlAsync(String action, int speed, Duration timeout) {
        DeviceCommand command = new DeviceCommand("cmd-async-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        log.info("[SCENARIO-RPC] Sending async RPC command: action={}, speed={}, timeout={}", action, speed, timeout);
        return mqttTemplate.sendAndReceiveAsync(
                "iot/rpc/device/control",
                command,
                CommandResponse.class,
                timeout
        );
    }

    /**
     * 模拟边缘设备端 RPC 控制应答监听器
     */
    @MqttListener(topics = "iot/rpc/device/control", qos = 1)
    public void onRpcControl(MqttMessage message) {
        if (message.getProperties() == null) {
            return;
        }
        String responseTopic = message.getProperties().getResponseTopic();
        byte[] correlationData = message.getProperties().getCorrelationData();

        if (responseTopic != null && correlationData != null) {
            String payloadStr = message.getPayload() != null
                    ? new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            if (payloadStr.contains("TIMEOUT")) {
                log.info("[SCENARIO-RPC] Simulating device timeout, suppressing response for topic [{}]", responseTopic);
                return;
            }

            log.info("[SCENARIO-RPC] Edge device received RPC command, responding to [{}]", responseTopic);
            CommandResponse response = new CommandResponse(
                    "cmd-ack-" + System.currentTimeMillis(),
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
                var defaultHandle = clientRegistry.getDefaultClientHandle();
                defaultHandle.getAsyncClient().publish(responseTopic, replyMsg);
            } catch (Exception e) {
                log.error("[SCENARIO-RPC] Failed to reply RPC command: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 发起声明式 @MqttReply 同步 RPC 设备控制指令
     */
    public CommandResponse sendDeclarativeSync(String action, int speed, Duration timeout) {
        DeviceCommand command = new DeviceCommand("cmd-decl-sync-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        log.info("[SCENARIO-RPC] Sending declarative sync RPC command: action={}, speed={}", action, speed);
        return mqttTemplate.sendAndReceive(
                "iot/rpc/device/declarative",
                command,
                CommandResponse.class,
                timeout
        );
    }

    /**
     * 发起声明式 @MqttReply 异步 RPC 设备控制指令
     */
    public CompletableFuture<CommandResponse> sendDeclarativeAsync(String action, int speed, Duration timeout) {
        DeviceCommand command = new DeviceCommand("cmd-decl-async-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        log.info("[SCENARIO-RPC] Sending declarative async RPC command: action={}, speed={}", action, speed);
        return mqttTemplate.sendAndReceiveAsync(
                "iot/rpc/device/declarative-async",
                command,
                CommandResponse.class,
                timeout
        );
    }

    /**
     * 声明式同步自动应答端点：直接返回业务 DTO，框架自动匹配入站 ResponseTopic 与 CorrelationData 回发
     */
    @MqttListener(topics = "iot/rpc/device/declarative", qos = 1)
    @MqttReply(qos = 1)
    public CommandResponse onDeclarativeSyncRpc(DeviceCommand command) {
        log.info("[SCENARIO-RPC] Declarative sync endpoint received command: {}", command);
        return new CommandResponse(
                command != null ? command.getCommandId() : "cmd-decl-sync",
                true,
                "DECLARATIVE_SYNC_OK",
                System.currentTimeMillis()
        );
    }

    /**
     * 声明式异步自动应答端点：返回 CompletableFuture，框架在未来就绪时自动绑定 CorrelationData 回发
     */
    @MqttListener(topics = "iot/rpc/device/declarative-async", qos = 1)
    @MqttReply(qos = 1)
    public CompletableFuture<CommandResponse> onDeclarativeAsyncRpc(DeviceCommand command) {
        log.info("[SCENARIO-RPC] Declarative async endpoint received command: {}", command);
        CommandResponse response = new CommandResponse(
                command != null ? command.getCommandId() : "cmd-decl-async",
                true,
                "DECLARATIVE_ASYNC_OK",
                System.currentTimeMillis()
        );
        return CompletableFuture.completedFuture(response);
    }
}
