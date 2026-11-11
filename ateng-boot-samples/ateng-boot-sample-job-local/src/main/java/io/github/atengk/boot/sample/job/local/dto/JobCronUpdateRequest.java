package io.github.atengk.boot.sample.job.local.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务 Cron 表达式动态更新请求参数
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobCronUpdateRequest implements Serializable {

    /**
     * 目标任务唯一标识
     */
    @NotBlank(message = "任务标识不能为空")
    private String jobName;

    /**
     * 新的 6 位标准 Cron 表达式
     */
    @NotBlank(message = "Cron 表达式不能为空")
    private String cronExpression;
}
