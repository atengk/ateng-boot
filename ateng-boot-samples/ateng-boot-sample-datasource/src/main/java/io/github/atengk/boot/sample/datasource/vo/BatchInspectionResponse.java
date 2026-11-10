package io.github.atengk.boot.sample.datasource.vo;

import io.github.atengk.boot.datasource.template.BatchFailure;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 批量巡检统计强类型展示视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchInspectionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否全部目标执行成功
     */
    private boolean allSuccess;

    /**
     * 巡检目标总数
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
     * 业务数据统计字典 (数据源键名或租户标识 -> 订单数量)
     */
    private Map<String, Long> counts;

    /**
     * 巡检失败项详情列表
     */
    private List<BatchFailure> failures;
}
