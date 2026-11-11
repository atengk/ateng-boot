package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 指定托管 TCP 客户端单向推流发送请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientSendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端注册实例名（default / binary-client / tls-client）
     */
    @NotBlank(message = "客户端实例名 clientName 不能为空")
    @Builder.Default
    private String clientName = "default";

    /**
     * 发送载荷格式（JSON / TEXT / HEX）
     */
    @NotBlank(message = "载荷类型 payloadType 不能为空")
    @Pattern(regexp = "JSON|TEXT|HEX", message = "payloadType 仅支持 JSON、TEXT 或 HEX")
    @Builder.Default
    private String payloadType = "JSON";

    /**
     * 可选协议指令路由标识（payloadType 为 TEXT 或 JSON 且非完整 JSON 时作为 command）
     */
    private String command;

    /**
     * 报文内容（TEXT 为字符串、JSON 为合法 JSON 串或文本、HEX 为十六进制字符串）
     */
    @NotBlank(message = "发送内容 content 不能为空")
    private String content;
}
