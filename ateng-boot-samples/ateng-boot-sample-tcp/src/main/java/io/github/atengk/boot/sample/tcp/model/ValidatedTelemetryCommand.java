package io.github.atengk.boot.sample.tcp.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 经过 JSR-303 约束标注的声明式遥测上行校验命令载荷
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidatedTelemetryCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备硬件编号
     */
    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;

    /**
     * 瞬时车速（km/h）
     */
    @NotNull(message = "speed 不能为空")
    @Min(value = 0, message = "speed 不能为负数")
    @Max(value = 300, message = "speed 不能超过 300 km/h")
    private Double speed;
}
