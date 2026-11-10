package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mqtt.model.MqttPublishRecord;
import io.github.atengk.boot.sample.mqtt.dto.BatchPublishItemRequest;
import io.github.atengk.boot.sample.mqtt.dto.BatchPublishRequest;
import io.github.atengk.boot.sample.mqtt.dto.RetainedStatusRequest;
import io.github.atengk.boot.sample.mqtt.service.BatchPublishScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量下发与保留消息协同体验端控制器
 * 提供多设备原子批量投递与设备影子保留消息对称发布与清除
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/mqtt/batch")
@RequiredArgsConstructor
public class MqttBatchAndRetainedController {

    private final BatchPublishScenarioService batchScenarioService;

    /**
     * 批量并发原子下发多条报文
     *
     * @param request 批量下发请求
     * @return 投递成功提示
     */
    @PostMapping("/publish")
    public Result<String> publishBatch(@Valid @RequestBody BatchPublishRequest request) {
        log.info("[HTTP-BATCH] 接收批量并发投递请求: size={}", request.getRecords().size());
        List<MqttPublishRecord> publishRecords = new ArrayList<>(request.getRecords().size());
        for (BatchPublishItemRequest item : request.getRecords()) {
            publishRecords.add(MqttPublishRecord.builder()
                    .topic(item.getTopic())
                    .payload(item.getPayload())
                    .qos(item.getQos() != null ? item.getQos() : 1)
                    .retained(item.isRetained())
                    .build());
        }
        batchScenarioService.publishBatch(publishRecords);
        return Result.success("成功原子批量投递 " + publishRecords.size() + " 条报文");
    }

    /**
     * 发布设备影子在线保留状态 (retained = true)
     *
     * @param request 保留状态请求
     * @return 发布结果
     */
    @PostMapping("/retained-status")
    public Result<String> publishRetainedStatus(@Valid @RequestBody RetainedStatusRequest request) {
        log.info("[HTTP-BATCH] 发布设备 [{}] 保留状态: {}", request.getDeviceId(), request.getStatus());
        batchScenarioService.publishRetainedStatus(request.getDeviceId(), request.getStatus());
        return Result.success("成功发布设备保留消息状态: " + request.getStatus());
    }

    /**
     * 对称清除设备影子保留状态 (发送 0 字节 retained 消息抹除 Broker 缓存)
     *
     * @param deviceId 设备标识
     * @return 清除结果
     */
    @DeleteMapping("/clear-retained")
    public Result<String> clearRetainedStatus(
            @NotBlank(message = "设备标识 deviceId 不能为空")
            @RequestParam("deviceId") String deviceId) {
        log.info("[HTTP-BATCH] 清除设备 [{}] 的保留消息", deviceId);
        batchScenarioService.clearRetainedStatus(deviceId);
        return Result.success("成功对称清空设备保留状态消息");
    }
}
