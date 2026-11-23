package io.github.atengk.boot.sample.liteflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 批量路由结算响应数据
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSettleResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 结算批次号
     */
    private String batchNo;

    /**
     * 总输入项目数量
     */
    private int totalItems;

    /**
     * 实际处理项目数量
     */
    private int processedCount;

    /**
     * 累计成功结算金额
     */
    private BigDecimal settledAmount;

    /**
     * 已结算的明细标识列表
     */
    private List<String> settledItemIds;

    /**
     * 触发税务降级保底的项目标识列表
     */
    private List<String> degradedItems;

    /**
     * 已成功纳税处理的项目标识列表
     */
    private List<String> taxAppliedItems;

    /**
     * 是否触发 endFlow 提前正常终止
     */
    private boolean terminatedEarly;

    /**
     * 批次处理汇总说明
     */
    private String summary;
}
