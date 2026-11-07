package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 工作日智能推演与跨度计算视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkdayCalcVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 基准计算日期
     */
    private LocalDate baseDate;

    /**
     * 基准日期是否属于法定工作日
     */
    private boolean workday;

    /**
     * 顺延 3 个工作日后的目标日期
     */
    private LocalDate plus3Workdays;

    /**
     * 两日期之间的工作日跨度天数
     */
    private Long betweenDays;
}
