package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 设备遥测数据上报请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryPublishRequest implements Serializable {

    @NotBlank(message = "设备标识 deviceId 不能为空")
    private String deviceId;

    @NotNull(message = "温度 temp 不能为空")
    private Double temp;

    @NotNull(message = "湿度 humidity 不能为空")
    private Double humidity;

    @Min(value = 0, message = "QoS 最小为 0")
    @Max(value = 2, message = "QoS 最大为 2")
    @Builder.Default
    private Integer qos = 1;

    private Map<String, String> headers;
}
