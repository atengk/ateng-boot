package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步远程命令执行请求载荷
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SshExecCommandRequest {

    /**
     * 待执行的远程 Shell 命令（必填）
     */
    @NotBlank(message = "命令内容不能为空")
    private String command;

    /**
     * 指定的具名服务器节点标识（可选，若为空则使用默认节点）
     */
    private String server;

    /**
     * 远程工作目录（可选）
     */
    private String workingDir;

    /**
     * 命令执行超时秒数（可选）
     */
    private Integer timeoutSeconds;
}
