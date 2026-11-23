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
 * 本地端口转发真实 TCP 流量交互请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshTcpEchoRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远端服务主机
     */
    @NotBlank(message = "远端服务主机不能为空")
    private String remoteHost;

    /**
     * 远端服务端口
     */
    @NotNull(message = "远端服务端口不能为空")
    @Min(1)
    @Max(65535)
    private Integer remotePort;

    /**
     * 测试交互报文
     */
    @NotBlank(message = "交互消息不能为空")
    private String message;
}
