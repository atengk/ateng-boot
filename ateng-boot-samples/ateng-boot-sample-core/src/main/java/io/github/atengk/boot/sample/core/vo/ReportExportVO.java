package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多业务线程池报表导出响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导出的报表名称
     */
    private String reportName;

    /**
     * 执行导出任务的工作线程名称
     */
    private String exportThreadName;

    /**
     * 执行报表统计工作的工作线程名称
     */
    private String reportThreadName;

    /**
     * 是否由两个不同的受管业务线程池完成舱壁隔离
     */
    private boolean bulkheadIsolated;

    /**
     * 导出协同耗时 (毫秒)
     */
    private long executionTimeMs;
}
