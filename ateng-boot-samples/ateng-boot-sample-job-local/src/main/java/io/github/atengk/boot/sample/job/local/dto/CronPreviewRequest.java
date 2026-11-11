package io.github.atengk.boot.sample.job.local.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Cron 表达式未来触发时钟点预演请求参数
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CronPreviewRequest implements Serializable {

    /**
     * 待校验与预演的标准 6 位 Cron 表达式
     */
    @NotBlank(message = "Cron 表达式不能为空")
    private String cron;

    /**
     * 预演未来触发时钟点数量
     */
    @Min(value = 1, message = "预演数量至少为 1 次")
    @Max(value = 50, message = "预演数量最多为 50 次")
    @Builder.Default
    private int count = 5;
}
