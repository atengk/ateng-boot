package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 异步批量任务强类型汇聚响应视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncBatchSupplyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 汇聚计算后的结果列表
     */
    private List<String> results;

    /**
     * 汇聚执行耗时（毫秒）
     */
    private long executionTimeMs;

    /**
     * 任务总数
     */
    private int totalTasks;
}
