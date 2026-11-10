package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 事务感知遥测上报请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TxTelemetryRequest implements Serializable {

    @NotBlank(message = "设备标识 deviceId 不能为空")
    private String deviceId;

    @NotNull(message = "温度 temp 不能为空")
    private Double temp;

    @NotNull(message = "湿度 humidity 不能为空")
    private Double humidity;

    private boolean shouldRollback;
}
