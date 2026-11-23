package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 原生 Sudo 安全管道提权执行请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshSudoRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待提权执行的命令
     */
    @NotBlank(message = "命令内容不能为空")
    private String command;

    /**
     * Sudo 提权认证密码（日志自动脱敏防泄露）
     */
    @NotBlank(message = "Sudo 密码不能为空")
    @ToString.Exclude
    private String sudoPassword;

    /**
     * 目标节点名称（缺省使用默认主节点）
     */
    private String serverName;
}
