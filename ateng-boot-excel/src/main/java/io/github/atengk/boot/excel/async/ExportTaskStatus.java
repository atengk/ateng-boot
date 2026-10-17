package io.github.atengk.boot.excel.async;

/**
 * Excel 异步导出任务状态枚举
 *
 * @author Ateng
 * @since 2026-10-17
 */
public enum ExportTaskStatus {

    /**
     * 排队等待中
     */
    PENDING,

    /**
     * 正在导出执行中
     */
    PROCESSING,

    /**
     * 导出完成并成功生成文件
     */
    SUCCESS,

    /**
     * 导出失败
     */
    FAILED,

    /**
     * 任务已取消
     */
    CANCELLED
}
