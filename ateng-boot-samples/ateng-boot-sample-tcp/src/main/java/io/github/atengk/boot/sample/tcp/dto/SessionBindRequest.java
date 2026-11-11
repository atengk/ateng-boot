package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会话与设备标识动态绑定请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标在线 TCP 会话唯一编号
     */
    @NotBlank(message = "会话编号 sessionId 不能为空")
    private String sessionId;

    /**
     * 待绑定的设备唯一编号
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    private String deviceId;
}
