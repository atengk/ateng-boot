package io.github.atengk.boot.sample.flyway.dto;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全租户批量基线初始化请求 DTO
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantBatchBaselineRequest {

    /**
     * 可选基线版本号（为空则继承默认基线版本）
     */
    private String version;

    /**
     * 可选基线描述文本
     */
    private String description;

    /**
     * 可选批量容错策略（为空时继承配置默认值）
     */
    private BatchExecutionPolicy policy;
}
