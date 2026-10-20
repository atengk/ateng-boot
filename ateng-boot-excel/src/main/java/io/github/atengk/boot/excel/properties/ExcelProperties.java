package io.github.atengk.boot.excel.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Excel 基础设施配置属性
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.excel")
public class ExcelProperties {

    /**
     * 是否启用 Excel 基础设施自动装配
     */
    private boolean enabled = true;

    /**
     * 流式读取默认批次大小（防止长列表占满堆内存）
     */
    private int defaultBatchSize = 1000;

    /**
     * 同步全量读取最大允许行数上限（默认 100,000 行，超过时快速失败以防御内存 OOM；大数据量请使用 readBatch 分批流式读取）
     */
    private int maxSyncRows = 100_000;

    /**
     * 大文件磁盘缓存临时目录（留空时使用系统临时目录）
     */
    private String tempDir;

    /**
     * Excel 企业级样式与模版主题配置
     */
    @NestedConfigurationProperty
    private ExcelStyleProperties style = new ExcelStyleProperties();

    /**
     * Excel 异步导出任务中心配置
     */
    @NestedConfigurationProperty
    private ExcelAsyncExportProperties asyncExport = new ExcelAsyncExportProperties();

    /**
     * Excel 海量数据流式分页导出配置
     */
    @NestedConfigurationProperty
    private ExcelStreamingExportProperties streamingExport = new ExcelStreamingExportProperties();

    /**
     * Excel 导入校验与错误熔断配置
     */
    @NestedConfigurationProperty
    private ExcelValidationProperties validation = new ExcelValidationProperties();
}
