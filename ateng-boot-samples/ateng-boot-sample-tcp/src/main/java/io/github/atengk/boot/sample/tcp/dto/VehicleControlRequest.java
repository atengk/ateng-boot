package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 车载终端远程控制下行指令请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleControlRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一编号
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    private String deviceId;

    /**
     * 下发控制命令字（如 LOCK / UNLOCK / SPEED_LIMIT / REBOOT）
     */
    @NotBlank(message = "控制指令 command 不能为空")
    private String command;

    /**
     * 附带控制参数
     */
    private Map<String, Object> params;
}
