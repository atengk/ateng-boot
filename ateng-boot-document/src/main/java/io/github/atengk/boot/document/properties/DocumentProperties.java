package io.github.atengk.boot.document.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

/**
 * 文档治理底座强类型配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.document")
public class DocumentProperties {

    /**
     * 是否启用文档治理基础设施底座
     */
    private boolean enabled = true;

    /**
     * 堆内存缓冲区容量上限，超过此阈值的大文档自动溢出至磁盘临时文件防 OOM
     */
    private DataSize inMemoryThreshold = DataSize.ofMegabytes(10);

    /**
     * 受管临时文件交换目录，缺省自动回退到操作系统临时目录下的 ateng-boot-document 子目录
     */
    private String tempDir = "";

    /**
     * Word 专属配置
     */
    @NestedConfigurationProperty
    private DocumentWordProperties word = new DocumentWordProperties();

    /**
     * PDF 专属配置
     */
    @NestedConfigurationProperty
    private DocumentPdfProperties pdf = new DocumentPdfProperties();

    /**
     * 防泄密水印专属配置
     */
    @NestedConfigurationProperty
    private DocumentWatermarkProperties watermark = new DocumentWatermarkProperties();

    /**
     * 获取有效的受管临时目录绝对路径
     *
     * @return 临时目录路径
     */
    public String resolveEffectiveTempDir() {
        if (tempDir != null && !tempDir.trim().isEmpty()) {
            return tempDir.trim();
        }
        String sysTmp = System.getProperty("java.io.tmpdir");
        if (sysTmp == null || sysTmp.isEmpty()) {
            sysTmp = ".";
        }
        return sysTmp.endsWith("/") || sysTmp.endsWith("\\")
                ? sysTmp + "ateng-boot-document"
                : sysTmp + "/ateng-boot-document";
    }
}
