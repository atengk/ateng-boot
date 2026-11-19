package io.github.atengk.boot.sample.flyway.dto;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量策略控制请求 DTO
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPolicyRequest {

    /**
     * 可选批量容错策略（为空时继承配置默认值）
     */
    private BatchExecutionPolicy policy;
}
