package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 按服务端监听端口（serverName）定向广播请求 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerPortBroadcastRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标服务端实例名称（如 default / binary-jt808 / tls-gateway）
     */
    @NotBlank(message = "服务端名称 serverName 不能为空")
    private String serverName;

    /**
     * 广播下行文本或告警消息内容
     */
    @NotBlank(message = "广播消息内容 message 不能为空")
    private String message;
}
