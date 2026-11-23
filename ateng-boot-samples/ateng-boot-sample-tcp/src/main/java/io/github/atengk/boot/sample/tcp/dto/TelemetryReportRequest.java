package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 车载定位遥测实时上行推流触发请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryReportRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备唯一编号
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    private String deviceId;

    /**
     * 传输编码协议（JSON 文本帧或 JT808 二进制帧）
     */
    @NotBlank(message = "协议类型 protocol 不能为空")
    @Pattern(regexp = "JSON|JT808", message = "protocol 仅支持 JSON 或 JT808")
    @Builder.Default
    private String protocol = "JSON";

    /**
     * 经度
     */
    @NotNull(message = "经度 longitude 不能为空")
    @DecimalMin(value = "-180.0", message = "经度不能小于 -180.0")
    @DecimalMax(value = "180.0", message = "经度不能大于 180.0")
    private Double longitude;

    /**
     * 纬度
     */
    @NotNull(message = "纬度 latitude 不能为空")
    @DecimalMin(value = "-90.0", message = "纬度不能小于 -90.0")
    @DecimalMax(value = "90.0", message = "纬度不能大于 90.0")
    private Double latitude;

    /**
     * 瞬时车速（km/h）
     */
    @NotNull(message = "车速 speed 不能为空")
    @DecimalMin(value = "0.0", message = "车速不能小于 0")
    @DecimalMax(value = "400.0", message = "车速不能超过 400")
    private Double speed;

    /**
     * 发动机状态（ON / OFF / ACC）
     */
    @NotBlank(message = "发动机状态 engineStatus 不能为空")
    @Builder.Default
    private String engineStatus = "ON";
}
