package io.github.atengk.boot.sample.excel.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异步导出任务状态与全链路追踪模型 VO
 *
 * @author Ateng
 * @since 2026-11-08
 */
public class AsyncExportStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导出任务唯一标识 ID
     */
    private String taskId;

    /**
     * 任务业务名称
     */
    private String taskName;

    /**
     * 任务当前执行状态
     */
    private String status;

    /**
     * 执行进度百分比 (0 - 100)
     */
    private int progress;

    /**
     * 生成的文件大小（字节数）
     */
    private Long fileSize;

    /**
     * 产物下载直链或相对端点 URL
     */
    private String downloadUrl;

    /**
     * 失败原因描述
     */
    private String errorMsg;

    /**
     * 任务创建时间
     */
    private LocalDateTime createTime;

    /**
     * 任务结束时间
     */
    private LocalDateTime finishTime;

    /**
     * 提交方线程上下文 traceId
     */
    private String requestTraceId;

    /**
     * 异步工作线程捕获并透传的 traceId
     */
    private String workerTraceId;

    public AsyncExportStatusVO() {
    }

    public AsyncExportStatusVO(String taskId, String taskName, String status, int progress, Long fileSize,
                               String downloadUrl, String errorMsg, LocalDateTime createTime,
                               LocalDateTime finishTime, String requestTraceId, String workerTraceId) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.status = status;
        this.progress = progress;
        this.fileSize = fileSize;
        this.downloadUrl = downloadUrl;
        this.errorMsg = errorMsg;
        this.createTime = createTime;
        this.finishTime = finishTime;
        this.requestTraceId = requestTraceId;
        this.workerTraceId = workerTraceId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }

    public String getRequestTraceId() {
        return requestTraceId;
    }

    public void setRequestTraceId(String requestTraceId) {
        this.requestTraceId = requestTraceId;
    }

    public String getWorkerTraceId() {
        return workerTraceId;
    }

    public void setWorkerTraceId(String workerTraceId) {
        this.workerTraceId = workerTraceId;
    }

    public static class Builder {
        private String taskId;
        private String taskName;
        private String status;
        private int progress;
        private Long fileSize;
        private String downloadUrl;
        private String errorMsg;
        private LocalDateTime createTime;
        private LocalDateTime finishTime;
        private String requestTraceId;
        private String workerTraceId;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskName(String taskName) {
            this.taskName = taskName;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder progress(int progress) {
            this.progress = progress;
            return this;
        }

        public Builder fileSize(Long fileSize) {
            this.fileSize = fileSize;
            return this;
        }

        public Builder downloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
            return this;
        }

        public Builder errorMsg(String errorMsg) {
            this.errorMsg = errorMsg;
            return this;
        }

        public Builder createTime(LocalDateTime createTime) {
            this.createTime = createTime;
            return this;
        }

        public Builder finishTime(LocalDateTime finishTime) {
            this.finishTime = finishTime;
            return this;
        }

        public Builder requestTraceId(String requestTraceId) {
            this.requestTraceId = requestTraceId;
            return this;
        }

        public Builder workerTraceId(String workerTraceId) {
            this.workerTraceId = workerTraceId;
            return this;
        }

        public AsyncExportStatusVO build() {
            return new AsyncExportStatusVO(taskId, taskName, status, progress, fileSize, downloadUrl,
                    errorMsg, createTime, finishTime, requestTraceId, workerTraceId);
        }
    }
}
