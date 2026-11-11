package io.github.atengk.boot.sample.job.snail.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调度中心工作流 DAG 新增/更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminWorkflowSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流名称
     */
    @NotBlank(message = "工作流名称不能为空")
    private String workflowName;

    /**
     * 触发类型 (1: CRON, 2: FIXED_RATE, 3: FIXED_DELAY)
     */
    private Integer triggerType;

    /**
     * 触发间隔或 Cron 表达式
     */
    @NotBlank(message = "触发间隔或Cron表达式不能为空")
    private String triggerInterval;

    /**
     * 工作流描述
     */
    private String description;
}
