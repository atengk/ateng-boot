package io.github.atengk.boot.document.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文档治理专属配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class DocumentPdfProperties {

    /**
     * 自定义中文字体物理文件路径（支持绝对路径或 classpath: 前缀）
     */
    private String fontPath = "";

    /**
     * 系统中文字体探测候选偏好名称列表（有序扫描）
     */
    private List<String> preferredFontNames = new ArrayList<>(List.of(
            "SimSun",
            "Microsoft YaHei",
            "Source Han Sans CN",
            "WenQuanYi Micro Hei",
            "PingFang SC"
    ));
}
