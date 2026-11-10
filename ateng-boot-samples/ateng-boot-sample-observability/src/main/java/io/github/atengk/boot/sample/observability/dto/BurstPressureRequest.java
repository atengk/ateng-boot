package io.github.atengk.boot.sample.observability.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 线程池瞬时突发高压请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BurstPressureRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 突发并发任务数量
     */
    @NotNull(message = "任务数量不能为空")
    @Min(value = 1, message = "任务数量至少为 1")
    private Integer taskCount;
}
