package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程端口转发反向 TCP 流量回显请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshRemoteTcpEchoRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * SSH 远端监听节点名称（缺省使用默认主节点）
     */
    private String serverName;

    /**
     * 远端 SSH 服务端绑定监听端口（传 0 自动分配）
     */
    @Min(value = 0, message = "远端端口范围必须为 0~65535")
    @Max(value = 65535, message = "远端端口范围必须为 0~65535")
    @Builder.Default
    private Integer remotePort = 0;

    /**
     * 本地目标服务主机
     */
    @NotBlank(message = "本地目标主机不能为空")
    @Builder.Default
    private String localHost = "127.0.0.1";

    /**
     * 本地目标服务端口
     */
    @NotNull(message = "本地目标端口不能为空")
    @Min(value = 1, message = "端口范围必须为 1~65535")
    @Max(value = 65535, message = "端口范围必须为 1~65535")
    private Integer localPort;

    /**
     * 测试交互报文
     */
    @NotBlank(message = "交互消息不能为空")
    private String message;
}
