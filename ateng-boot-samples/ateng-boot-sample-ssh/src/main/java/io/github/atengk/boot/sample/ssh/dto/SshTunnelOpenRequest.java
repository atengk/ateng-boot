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
 * 本地端口转发建立请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshTunnelOpenRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * SSH 中继主机节点名称（缺省使用默认主节点）
     */
    private String serverName;

    /**
     * 本地期望绑定端口（0 表示由操作系统分配可用随机端口）
     */
    @Builder.Default
    private Integer localPort = 0;

    /**
     * 目标内网主机地址
     */
    @NotBlank(message = "远端目标主机不能为空")
    private String remoteHost;

    /**
     * 目标内网服务端口
     */
    @NotNull(message = "远端目标端口不能为空")
    @Min(value = 1, message = "端口范围必须为 1~65535")
    @Max(value = 65535, message = "端口范围必须为 1~65535")
    private Integer remotePort;
}
