package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 原生 Sudo 提权安全管道执行请求载荷
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SshSudoCommandRequest {

    /**
     * 待执行的提权命令（必填）
     */
    @NotBlank(message = "提权命令不能为空")
    private String command;

    /**
     * Sudo 提权密码（必填）
     */
    @NotBlank(message = "提权密码不能为空")
    private String sudoPassword;

    /**
     * 指定的具名服务器节点标识（可选）
     */
    private String server;
}
