package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 车载终端运行参数同步查询请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleParamQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一编号
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    private String deviceId;

    /**
     * 查询参数大类（如 SYSTEM / BATTERY / NETWORK）
     */
    @NotBlank(message = "参数类型 paramType 不能为空")
    @Builder.Default
    private String paramType = "SYSTEM";
}
