package io.github.atengk.boot.sample.datasource.vo;

import io.github.atengk.boot.datasource.template.BatchExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带返回值批处理结果聚合强类型展示视图对象
 * <p>
 * 隔离底层 {@link BatchExecutionResult} 传输边界，仅向调用方暴露脱敏后的聚合结果与失败摘要。
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchResultAggregationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否全部目标执行成功
     */
    private boolean allSuccess;

    /**
     * 执行目标总数
     */
    private int totalCount;

    /**
     * 成功执行目标数
     */
    private int successCount;

    /**
     * 失败执行目标数
     */
    private int failureCount;

    /**
     * 各成功目标计算结果映射（数据源 Key 或租户 ID -> 统计数值）
     */
    private Map<String, Long> results;

    /**
     * 失败目标标识清单
     */
    private List<String> failedTargets;

    /**
     * 脱敏后的失败明细列表（不含原始异常堆栈）
     */
    private List<FailureItem> failures;

    /**
     * 从底层批处理结果对象安全投影为展示视图对象
     *
     * @param result 底层泛型批处理聚合结果
     * @return 强类型视图响应对象
     */
    public static BatchResultAggregationResponse from(BatchExecutionResult<Long> result) {
        if (result == null) {
            return BatchResultAggregationResponse.builder()
                    .allSuccess(true)
                    .totalCount(0)
                    .successCount(0)
                    .failureCount(0)
                    .results(Collections.emptyMap())
                    .failedTargets(Collections.emptyList())
                    .failures(Collections.emptyList())
                    .build();
        }
        List<FailureItem> safeFailures = result.getFailures().stream()
                .map(failure -> FailureItem.builder()
                        .target(failure.getTarget())
                        .errorMessage(failure.getErrorMessage())
                        .build())
                .toList();
        return BatchResultAggregationResponse.builder()
                .allSuccess(result.isAllSuccess())
                .totalCount(result.getTotalCount())
                .successCount(result.getSuccessCount())
                .failureCount(result.getFailureCount())
                .results(new LinkedHashMap<>(result.getResults()))
                .failedTargets(result.getFailedTargets())
                .failures(safeFailures)
                .build();
    }

    /**
     * 单项失败脱敏摘要视图
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 执行目标标识（数据源 Key 或租户 ID）
         */
        private String target;

        /**
         * 失败原因摘要
         */
        private String errorMessage;
    }
}
