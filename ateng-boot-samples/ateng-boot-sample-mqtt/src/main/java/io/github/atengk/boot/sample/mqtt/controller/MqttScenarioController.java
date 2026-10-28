package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mqtt.codec.HexPayload;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.listener.DeviceTelemetryListener;
import io.github.atengk.boot.sample.mqtt.model.CommandResponse;
import io.github.atengk.boot.sample.mqtt.model.DeviceCommand;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import io.github.atengk.boot.sample.mqtt.service.MqttTransactionalOrderService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MQTT 场景体验与端到端控制控制器
 *
 * @author Ateng
 * @since 2026-10-23
 */
@RestController
@RequestMapping("/api/mqtt")
public class MqttScenarioController {

    private final MqttTemplate mqttTemplate;
    private final DeviceTelemetryListener telemetryListener;
    private final MqttTransactionalOrderService transactionalService;

    public MqttScenarioController(MqttTemplate mqttTemplate,
                                  DeviceTelemetryListener telemetryListener,
                                  MqttTransactionalOrderService transactionalService) {
        this.mqttTemplate = mqttTemplate;
        this.telemetryListener = telemetryListener;
        this.transactionalService = transactionalService;
    }

    /**
     * 1. 发布设备上行遥测数据
     */
    @PostMapping("/publish/telemetry")
    public Result<String> publishTelemetry(@RequestParam String deviceId,
                                           @RequestParam double temp,
                                           @RequestParam double humidity) {
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        mqttTemplate.publish("iot/devices/" + deviceId + "/telemetry", telemetry, 1);
        return Result.success("Telemetry published successfully");
    }

    /**
     * 2. 发布国标十六进制报文
     */
    @PostMapping("/publish/hex")
    public Result<String> publishHex(@RequestParam String deviceId,
                                     @RequestParam String hexString) {
        HexPayload payload = HexPayload.of(hexString);
        mqttTemplate.publish("iot/devices/" + deviceId + "/hex", payload, 1);
        return Result.success("Hex message published successfully");
    }

    /**
     * 3. 发布共享集群度量
     */
    @PostMapping("/publish/cluster-metric")
    public Result<String> publishClusterMetric(@RequestParam String metric) {
        mqttTemplate.publish("iot/cluster/metrics", metric, 1);
        return Result.success("Cluster metric published successfully");
    }

    /**
     * 4. 同步 RPC 下行控制指令
     */
    @PostMapping("/rpc/control")
    public Result<CommandResponse> sendControlRpc(@RequestParam String action,
                                                  @RequestParam(required = false, defaultValue = "100") int speed) {
        DeviceCommand command = new DeviceCommand("cmd-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        CommandResponse response = mqttTemplate.sendAndReceive(
                "iot/rpc/device/control",
                command,
                CommandResponse.class,
                Duration.ofSeconds(5)
        );
        return Result.success(response);
    }

    /**
     * 5. 本地事务安全协同发信
     */
    @PostMapping("/tx/telemetry")
    public Result<String> sendTelemetryWithTx(@RequestParam String deviceId,
                                              @RequestParam double temp,
                                              @RequestParam double humidity) {
        transactionalService.recordTelemetryWithTx(deviceId, temp, humidity);
        return Result.success("Transactional telemetry registered successfully");
    }

    /**
     * 6. 查询监听器收到的最新遥测数据
     */
    @GetMapping("/telemetries")
    public Result<List<DeviceTelemetry>> getReceivedTelemetries() {
        return Result.success(telemetryListener.getReceivedTelemetries());
    }
}
