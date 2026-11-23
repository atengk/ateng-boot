package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 长任务按行流式消费命令日志请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshStreamLogRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待执行的长任务滚动命令
     */
    @NotBlank(message = "命令内容不能为空")
    private String command;

    /**
     * 目标节点名称（缺省使用默认主节点）
     */
    private String serverName;
}
