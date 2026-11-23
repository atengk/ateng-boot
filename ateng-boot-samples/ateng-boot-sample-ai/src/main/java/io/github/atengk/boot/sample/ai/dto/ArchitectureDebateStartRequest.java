package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 架构评审对抗式辩论启动请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchitectureDebateStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 架构方案标题
     */
    @NotBlank(message = "方案标题 proposalTitle 不能为空")
    private String proposalTitle;

    /**
     * 方案详细设计说明
     */
    @NotBlank(message = "方案描述 proposalDesc 不能为空")
    private String proposalDesc;

    /**
     * 目标并发峰值 QPS
     */
    @NotNull(message = "目标 QPS targetQps 不能为空")
    @Min(value = 1, message = "目标 QPS 必须大于 0")
    private Integer targetQps;

    /**
     * 是否故意制造对抗死锁 (用于测试看门狗熔断)
     */
    private Boolean forceDeadlock;
}
