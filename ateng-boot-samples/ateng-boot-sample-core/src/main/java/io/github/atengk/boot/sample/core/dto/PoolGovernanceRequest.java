package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 线程池治理与动态热调参请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolGovernanceRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标业务线程池名称 (如 export / report)
     */
    @NotBlank(message = "线程池名称不能为空")
    private String poolName;

    /**
     * 调整后的核心线程数
     */
    @NotNull(message = "新核心线程数不能为空")
    @Min(value = 1, message = "核心线程数最小为 1")
    @Max(value = 200, message = "核心线程数最大为 200")
    private Integer newCorePoolSize;

    /**
     * 调整后的最大线程数
     */
    @NotNull(message = "新最大线程数不能为空")
    @Min(value = 1, message = "最大线程数最小为 1")
    @Max(value = 500, message = "最大线程数最大为 500")
    private Integer newMaxPoolSize;
}
