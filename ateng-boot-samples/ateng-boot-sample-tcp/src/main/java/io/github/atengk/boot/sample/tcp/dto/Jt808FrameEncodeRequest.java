package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * JT/T 808 国标复合二进制帧编码演练请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Jt808FrameEncodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 终端设备号（最长 12 位 ASCII）
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    @Size(max = 12, message = "JT/T 808 设备编号长度不能超过 12 字符")
    private String deviceId;

    /**
     * 报文消息流水号
     */
    @NotNull(message = "消息流水号 seq 不能为空")
    @Min(value = 1, message = "消息流水号 seq 必须大于等于 1")
    private Long seq;

    /**
     * 经度（-180.0 ~ 180.0）
     */
    @NotNull(message = "经度 longitude 不能为空")
    @DecimalMin(value = "-180.0", message = "经度不能小于 -180.0")
    @DecimalMax(value = "180.0", message = "经度不能大于 180.0")
    private Double longitude;

    /**
     * 纬度（-90.0 ~ 90.0）
     */
    @NotNull(message = "纬度 latitude 不能为空")
    @DecimalMin(value = "-90.0", message = "纬度不能小于 -90.0")
    @DecimalMax(value = "90.0", message = "纬度不能大于 90.0")
    private Double latitude;

    /**
     * 车速（km/h，0.0 ~ 400.0）
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
