package io.github.atengk.boot.excel.properties;

import lombok.Data;

/**
 * Excel 海量数据流式分页导出配置属性
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ExcelStreamingExportProperties {

    /**
     * 单个工作表最大允许写入行数上限（超过时自动换 Sheet 分割，Excel 物理极限为 1,048,576 行，默认 1,000,000 行）
     */
    private int maxRowsPerSheet = 1_000_000;

    /**
     * 流式分页拉取默认步长大小（默认 1000 行/页）
     */
    private int defaultPageSize = 1000;
}
