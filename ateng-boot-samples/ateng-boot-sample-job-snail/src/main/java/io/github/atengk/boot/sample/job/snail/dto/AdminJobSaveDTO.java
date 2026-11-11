package io.github.atengk.boot.sample.job.snail.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调度中心任务新增/更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminJobSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务名称
     */
    @NotBlank(message = "任务名称不能为空")
    private String jobName;

    /**
     * 执行器名称
     */
    @NotBlank(message = "执行器名称不能为空")
    private String executorInfo;

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
     * 执行器路由策略 (1: 轮询, 2: 随机, 3: 广播)
     */
    private Integer routeKey;

    /**
     * 任务描述
     */
    private String description;
}
