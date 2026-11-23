package io.github.atengk.boot.sample.mqtt.dto;

import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;

import java.io.Serializable;
import java.util.Map;

/**
 * 设备遥测监听消费巡检回显模型
 *
 * @author Ateng
 * @since 2026-11-10
 */
public record TelemetryInspectionRecord(
        String deviceId,
        String topic,
        DeviceTelemetry telemetry,
        String traceId,
        String tenantId,
        String userId,
        Map<String, Object> headers,
        long timestamp
) implements Serializable {}
