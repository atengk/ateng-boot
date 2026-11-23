package io.github.atengk.boot.sample.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多智能体死循环硬看门狗熔断测试请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentWatchdogRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 测试任务目标
     */
    private String taskObjective;
}
