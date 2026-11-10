package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 线程上下文动态切源请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshContextSwitchRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标节点名称
     */
    @NotBlank(message = "目标节点名称不能为空")
    private String serverName;

    /**
     * 待执行命令
     */
    @NotBlank(message = "待执行命令不能为空")
    private String command;
}
