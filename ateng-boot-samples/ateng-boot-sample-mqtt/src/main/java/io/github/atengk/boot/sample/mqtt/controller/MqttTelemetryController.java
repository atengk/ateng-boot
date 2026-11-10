package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mqtt.dto.TelemetryInspectionRecord;
import io.github.atengk.boot.sample.mqtt.dto.TelemetryPublishRequest;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import io.github.atengk.boot.sample.mqtt.service.DeviceTelemetryScenarioService;
import io.github.atengk.boot.sample.mqtt.service.TelemetryInspectionHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private final DeviceTelemetryScenarioService scenarioService;
    private final TelemetryInspectionHolder inspectionHolder;

    /**
     * 上报设备遥测数据并同步等待监听消费回显
     *
     * @param request 遥测上报请求
     * @return 监听器消费捕获记录（含还原的 4D TraceId、租户、用户与 Header 字典）
     */
    @PostMapping("/publish")
    public Result<TelemetryInspectionRecord> publishTelemetry(@Valid @RequestBody TelemetryPublishRequest request) {
        log.info("[HTTP-TELEMETRY] 接收设备遥测上报请求: {}", request);

        // 注册对该设备消费事件的短时等待
        CompletableFuture<TelemetryInspectionRecord> waitFuture = inspectionHolder.registerWait(request.getDeviceId());

        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            scenarioService.publishTelemetryWithHeaders(
                    request.getDeviceId(),
                    request.getTemp(),
                    request.getHumidity(),
                    request.getHeaders()
            );
        } else {
            scenarioService.publishTelemetry(
                    request.getDeviceId(),
                    request.getTemp(),
                    request.getHumidity()
            );
        }

        try {
            // 短时等待被动监听器消费（测试与本地体验默认在 3 秒内完成）
            TelemetryInspectionRecord record = waitFuture.get(3, TimeUnit.SECONDS);
            return Result.success(record);
        } catch (TimeoutException e) {
            log.warn("[HTTP-TELEMETRY] 等待设备 [{}] 监听消费回显超时，返回已投递快照", request.getDeviceId());
            return Result.success(new TelemetryInspectionRecord(
                    request.getDeviceId(),
                    "iot/devices/" + request.getDeviceId() + "/telemetry",
                    new DeviceTelemetry(request.getDeviceId(), request.getTemp(), request.getHumidity(), System.currentTimeMillis()),
                    null,
                    null,
                    null,
                    null,
                    System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("[HTTP-TELEMETRY] 设备 [{}] 遥测投递等待异常", request.getDeviceId(), e);
            return Result.failure("遥测投递或回显异常: " + e.getMessage());
        }
    }

    /**
     * 查询指定设备或最新的遥测消费记录
     *
     * @param deviceId 设备标识（可选）
     * @return 遥测消费记录
     */
    @GetMapping("/latest")
    public Result<TelemetryInspectionRecord> getLatestTelemetry(@RequestParam(value = "deviceId", required = false) String deviceId) {
        TelemetryInspectionRecord record = inspectionHolder.getLatest(deviceId);
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
