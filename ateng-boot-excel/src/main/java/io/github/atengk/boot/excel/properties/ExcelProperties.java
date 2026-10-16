package io.github.atengk.boot.excel.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
     * 大文件磁盘缓存临时目录（留空时使用系统临时目录）
     */
    private String tempDir;

    /**
     * Excel 企业级样式与模版主题配置
     */
    private ExcelStyleProperties style = new ExcelStyleProperties();
}
