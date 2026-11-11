package io.github.atengk.boot.sample.job.local.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 编程式动态注册定时任务请求参数
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobDynamicRegisterRequest implements Serializable {

    /**
     * 待注册任务唯一标识
     */
    @NotBlank(message = "任务标识不能为空")
    private String jobName;

    /**
     * 标准 6 位 Cron 表达式
     */
    @NotBlank(message = "Cron 表达式不能为空")
    private String cron;

    /**
     * 任务业务中文描述
     */
    private String description;
}
