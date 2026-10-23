package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 本地端口转发隧道开启请求载荷
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SshTunnelRequest {

    /**
     * 指定的跳板机具名节点标识（可选，若为空则使用默认主节点）
     */
    private String server;

    /**
     * 本地监听端口（传 0 表示由系统分配随机可用端口）
     */
    @Min(value = 0, message = "本地端口不能为负数")
    @Max(value = 65535, message = "本地端口不能超过65535")
    private int localPort = 0;

    /**
     * 目标远端服务主机（必填）
     */
    @NotBlank(message = "目标主机不能为空")
    private String remoteHost;

    /**
     * 目标远端服务端口（必填）
     */
    @Min(value = 1, message = "远端端口必须大于0")
    @Max(value = 65535, message = "远端端口不能超过65535")
    private int remotePort;
}
