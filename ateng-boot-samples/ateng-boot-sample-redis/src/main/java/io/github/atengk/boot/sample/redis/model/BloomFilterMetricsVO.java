package io.github.atengk.boot.sample.redis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 布隆过滤器运行监控指标视图对象
 * 严守强类型契约，消除裸 Map<String, Object> 带来的类型漂移隐患
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloomFilterMetricsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标布隆过滤器名称
     */
    private String filterName;

    /**
     * 过滤器当前已估算插入的元素总数
     */
    private long count;

    /**
     * 预热声明的最大容纳元素预期基数
     */
    private long expectedInsertions;

    /**
     * 当前容量使用率 (0.0 ~ 1.0)
     */
    private double usageRate;

    /**
     * 容许误判概率 (如 0.01 表示 1%)
     */
    private double falseProbability;
}
