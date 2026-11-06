package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多智能体协同任务启动请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentTaskRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 全局任务总体目标
     */
    @NotBlank(message = "任务总目标 taskObjective 不能为空")
    private String taskObjective;

    /**
     * 详细需求或背景说明
     */
    private String requirementDetails;
}
