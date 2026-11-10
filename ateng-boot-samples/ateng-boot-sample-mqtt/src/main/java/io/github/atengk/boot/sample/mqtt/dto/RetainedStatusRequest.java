package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 设备影子保留状态请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetainedStatusRequest implements Serializable {

    @NotBlank(message = "设备标识 deviceId 不能为空")
    private String deviceId;

    @NotBlank(message = "设备状态 status 不能为空")
    private String status;
}
