package io.github.atengk.boot.sample.tcp.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 车机远程控制下发请求 DTO
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleCommandRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一编号
     */
    @NotBlank(message = "设备标识 deviceId 不能为空")
    private String deviceId;

    /**
     * 控制指令码（LOCK / UNLOCK / SPEED_LIMIT / REBOOT）
     */
    @NotBlank(message = "控制指令 command 不能为空")
    private String command;

    /**
     * 扩展控制参数字典（如限速阈值 speedThreshold 等）
     */
    private Map<String, Object> params;
}
