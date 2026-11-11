package io.github.atengk.boot.sample.job.local.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务固定频率调度周期动态更新请求参数
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRateUpdateRequest implements Serializable {

    /**
     * 目标任务唯一标识
     */
    @NotBlank(message = "任务标识不能为空")
    private String jobName;

    /**
     * 新的固定频率调度间隔（如 5s, 1m, 1h）
     */
    @NotBlank(message = "固定频率调度间隔不能为空")
    @Pattern(regexp = "^(PT.*|[0-9]+(?:\\.[0-9]+)?(?:ms|s|m|h|d)?)$",
            message = "固定频率格式不合法（支持如 500ms, 10s, 5m, 1h 或 ISO-8601 格式 PT10S）")
    private String fixedRate;
}
