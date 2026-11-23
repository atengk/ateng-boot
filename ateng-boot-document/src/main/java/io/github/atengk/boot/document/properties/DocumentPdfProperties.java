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

    /**
     * PDF 默认页面渲染分辨率 DPI
     */
    private float defaultRenderDpi = 150.0f;

    /**
     * PDF 页面渲染允许的最大 DPI 安全上限，防止超高分辨率渲染导致 OOM
     */
    private float maxRenderDpi = 600.0f;

    /**
     * PDF 默认单次最多允许渲染页数安全上限
     */
    private int defaultMaxRenderPages = 50;

    /**
     * PDF 黑涂脱敏栅格化默认输出 DPI
     */
    private float redactionDpi = 200.0f;
}
