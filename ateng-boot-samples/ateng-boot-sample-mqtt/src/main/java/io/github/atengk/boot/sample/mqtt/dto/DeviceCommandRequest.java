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

/**
 * 设备下行控制指令请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandRequest implements Serializable {

    @NotBlank(message = "控制动作 action 不能为空")
    private String action;

    @NotNull(message = "设定转速 speed 不能为空")
    @Min(value = 0, message = "转速 speed 不能小于 0")
    @Max(value = 10000, message = "转速 speed 不能超过 10000")
    private Integer speed;

    @Min(value = 1, message = "超时时间至少为 1 秒")
    @Max(value = 60, message = "超时时间不能超过 60 秒")
    @Builder.Default
    private Integer timeoutSeconds = 5;
}
