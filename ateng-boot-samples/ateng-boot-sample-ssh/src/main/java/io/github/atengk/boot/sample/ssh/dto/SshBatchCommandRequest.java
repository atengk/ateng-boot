package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 集群多节点批量并发巡检执行请求载荷
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SshBatchCommandRequest {

    /**
     * 待执行的目标服务器具名节点名称列表（必填）
     */
    @NotEmpty(message = "集群目标节点列表不能为空")
    private List<String> servers;

    /**
     * 待在各节点并发执行的命令（必填）
     */
    @NotBlank(message = "巡检命令不能为空")
    private String command;
}
