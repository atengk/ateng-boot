package io.github.atengk.boot.sample.flyway.dto;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 指定目标数据源集合批量操作请求 DTO
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTargetsRequest {

    /**
     * 目标数据源标识集合（如 master、log_db、tenant_1001）
     */
    @NotEmpty(message = "目标数据源集合 (targetKeys) 不能为空")
    private List<@NotBlank(message = "目标数据源标识不能为空") String> targetKeys;

    /**
     * 可选批量容错策略（为空时继承配置默认值）
     */
    private BatchExecutionPolicy policy;
}
