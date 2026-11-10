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
 * 两级跳板机级联穿透请求 DTO (Client -> Jump1 -> Jump2 -> Target)
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshProxyJumpRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标隔离内网主机
     */
    @NotBlank(message = "目标主机不能为空")
    private String targetHost;

    /**
     * 目标主机 SSH 端口
     */
    @NotNull(message = "目标端口不能为空")
    @Min(1)
    @Max(65535)
    @Builder.Default
    private Integer targetPort = 22;

    /**
     * 第一级跳板机主机
     */
    @NotBlank(message = "跳板机1主机不能为空")
    private String jump1Host;

    /**
     * 第一级跳板机 SSH 端口
     */
    @NotNull(message = "跳板机1端口不能为空")
    @Min(1)
    @Max(65535)
    @Builder.Default
    private Integer jump1Port = 22;

    /**
     * 第二级跳板机主机
     */
    @NotBlank(message = "跳板机2主机不能为空")
    private String jump2Host;

    /**
     * 第二级跳板机 SSH 端口
     */
    @NotNull(message = "跳板机2端口不能为空")
    @Min(1)
    @Max(65535)
    @Builder.Default
    private Integer jump2Port = 22;

    /**
     * 目标主机上执行的命令
     */
    @NotBlank(message = "待执行命令不能为空")
    private String command;
}
