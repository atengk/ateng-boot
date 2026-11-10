package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工业国标十六进制报文发布请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HexPublishRequest implements Serializable {

    @NotBlank(message = "设备标识 deviceId 不能为空")
    private String deviceId;

    @NotBlank(message = "十六进制报文 hexData 不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]+$", message = "hexData 必须为合法的十六进制字符序列 (0-9, a-f, A-F)")
    private String hexData;
}
