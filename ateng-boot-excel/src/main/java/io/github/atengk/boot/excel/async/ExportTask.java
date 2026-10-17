package io.github.atengk.boot.excel.async;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Excel 异步导出任务模型
 * <p>
 * 封装任务标识、当前状态、生成进度、落盘路径及错误信息。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务全局唯一 ID
     */
    private String taskId;

    /**
     * 任务名称或业务描述
     */
    private String taskName;

    /**
     * 任务状态
     */
    @Builder.Default
    private ExportTaskStatus status = ExportTaskStatus.PENDING;

    /**
     * 执行进度百分比 (0 - 100)
     */
    @Builder.Default
    private int progress = 0;

    /**
     * 生成的 Excel 文件绝对路径
     */
    private String filePath;

    /**
     * 文件下载链接（若业务存储在 OSS 等可回填）
     */
    private String downloadUrl;

    /**
     * 生成的文件大小（字节数）
     */
    private Long fileSize;

    /**
     * 失败原因描述
     */
    private String errorMsg;

    /**
     * 任务创建时间
     */
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();

    /**
     * 任务结束时间
     */
    private LocalDateTime finishTime;
}
