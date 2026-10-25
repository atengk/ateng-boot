package io.github.atengk.boot.sample.job.local.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 动态定时任务注册请求参数
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
public class DynamicJobRegisterRequest {

    @NotBlank(message = "任务名称不能为空")
    private String jobName;

    private String description;

    private String cron;

    private String fixedRate;

    private String fixedDelay;
}
