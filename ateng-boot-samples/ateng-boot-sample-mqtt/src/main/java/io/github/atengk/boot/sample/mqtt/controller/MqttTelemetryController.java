package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mqtt.dto.TelemetryInspectionRecord;
import io.github.atengk.boot.sample.mqtt.dto.TelemetryPublishRequest;
import io.github.atengk.boot.sample.mqtt.service.DeviceTelemetryScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 设备遥测与声明式消费体验端控制器
 * 提供遥测数据上报、REST 路径变量提取与四维上下文（TraceId/租户/用户）回显体验
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/mqtt/telemetry")
@RequiredArgsConstructor
public class MqttTelemetryController {

    private static final Duration DEFAULT_TELEMETRY_AWAIT_TIMEOUT = Duration.ofSeconds(3);

    private final DeviceTelemetryScenarioService scenarioService;

    /**
     * 上报设备遥测数据并同步等待监听消费回显
     *
     * @param request 遥测上报请求
     * @return 监听器消费捕获记录（含还原的 4D TraceId、租户、用户与 Header 字典）
     */
    @PostMapping("/publish")
    public Result<TelemetryInspectionRecord> publishTelemetry(@Valid @RequestBody TelemetryPublishRequest request) {
        log.info("[HTTP-TELEMETRY] 接收设备遥测上报请求: {}", request);
        TelemetryInspectionRecord record = scenarioService.publishAndAwait(request, DEFAULT_TELEMETRY_AWAIT_TIMEOUT);
        return Result.success(record);
    }

    /**
     * 查询指定设备或最新的遥测消费记录
     *
     * @param deviceId 设备标识（可选）
     * @return 遥测消费记录
     */
    @GetMapping("/latest")
    public Result<TelemetryInspectionRecord> getLatestTelemetry(@RequestParam(value = "deviceId", required = false) String deviceId) {
        TelemetryInspectionRecord record = scenarioService.getLatestTelemetry(deviceId);
        return Result.success(record);
    }

    /**
     * 发布集群度量数据至共享订阅主题 ($share/telemetry-worker/iot/cluster/metrics)
     *
     * @param metricJson 度量 JSON 报文
     * @return 投递提示
     */
    @PostMapping("/cluster-metric")
    public Result<String> publishClusterMetric(@RequestParam(value = "metric", defaultValue = "{\"cpu\": 42.5, \"memory\": 68.2}") String metricJson) {
        scenarioService.publishClusterMetric(metricJson);
        return Result.success("集群共享度量已投递至 $share/telemetry-worker/iot/cluster/metrics");
    }
}
