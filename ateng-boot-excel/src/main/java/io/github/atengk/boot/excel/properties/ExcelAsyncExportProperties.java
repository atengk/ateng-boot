package io.github.atengk.boot.excel.properties;

import lombok.Data;

import java.time.Duration;

/**
 * Excel 异步导出任务配置属性
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
public class ExcelAsyncExportProperties {

    /**
     * 是否启用异步导出中心（默认 true）
     */
    private boolean enabled = true;

    /**
     * 异步导出文件临时存放目录（留空时使用系统临时目录下的 ateng-excel-async 目录）
     */
    private String tempDir;

    /**
     * 临时文件默认保留过期时长（默认 24h，支持 24h、1d 等标准 Duration 格式）
     */
    private Duration retentionPeriod = Duration.ofHours(24);

    /**
     * 异步执行线程池核心线程数（默认 4）
     */
    private int corePoolSize = 4;

    /**
     * 异步执行线程池最大线程数（默认 8）
     */
    private int maxPoolSize = 8;

    /**
     * 任务等待队列容量（默认 200）
     */
    private int queueCapacity = 200;

    /**
     * 兼容旧版小时数值获取
     */
    public int getRetentionHours() {
        return retentionPeriod != null ? (int) retentionPeriod.toHours() : 24;
    }

    /**
     * 兼容旧版小时数值设置
     */
    public void setRetentionHours(int retentionHours) {
        this.retentionPeriod = Duration.ofHours(retentionHours);
    }
}
