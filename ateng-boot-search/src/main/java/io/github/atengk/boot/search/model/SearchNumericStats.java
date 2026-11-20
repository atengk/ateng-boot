package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 强类型数值统计聚合结果模型 (count / min / max / avg / sum)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchNumericStats implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 参与统计的有效文档数量
     */
    private long count;

    /**
     * 最小值（无有效文档时为 null）
     */
    private Double min;

    /**
     * 最大值（无有效文档时为 null）
     */
    private Double max;

    /**
     * 平均值（无有效文档时为 null）
     */
    private Double avg;

    /**
     * 求和值（无有效文档时为 null）
     */
    private Double sum;
}
