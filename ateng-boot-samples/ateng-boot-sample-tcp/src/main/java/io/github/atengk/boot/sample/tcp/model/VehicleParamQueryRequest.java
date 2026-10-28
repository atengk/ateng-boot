package io.github.atengk.boot.sample.tcp.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 车机全双工参数查询请求 DTO
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleParamQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一编号
     */
    @NotBlank(message = "设备标识 deviceId 不能为空")
    private String deviceId;

    /**
     * 参数类型（默认 SYSTEM）
     */
    @NotBlank(message = "参数类型 paramType 不能为空")
    private String paramType = "SYSTEM";
}
