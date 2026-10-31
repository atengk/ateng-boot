package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.pdfbox.rendering.ImageType;

/**
 * PDF 页面转高清图片/缩略图高级配置选项
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfRenderOptions {

    /**
     * 渲染分辨率 DPI（默认 150.0f，推荐范围 72.0f ~ 600.0f）
     */
    @Builder.Default
    private float dpi = 150.0f;

    /**
     * 图像颜色模型类型（默认 ImageType.RGB，支持 RGB, ARGB, GRAY, BGR）
     */
    @Builder.Default
    private ImageType imageType = ImageType.RGB;

    /**
     * 输出图像格式（默认 "PNG"，支持 PNG, JPEG, JPG 等）
     */
    @Builder.Default
    private String format = "PNG";

    /**
     * 批量渲染单次最大页数安全阈值（默认 50 页，防 OOM 堆内存积压）
     */
    @Builder.Default
    private int maxPages = 50;

    /**
     * 创建默认渲染配置（150 DPI, RGB, PNG, 50 页上限）
     *
     * @return 默认配置实例
     */
    public static PdfRenderOptions defaults() {
        return PdfRenderOptions.builder().build();
    }

    /**
     * 创建指定 DPI 的渲染配置
     *
     * @param dpi 分辨率 DPI
     * @return 配置实例
     */
    public static PdfRenderOptions ofDpi(float dpi) {
        return PdfRenderOptions.builder().dpi(dpi).build();
    }

    /**
     * 创建快速缩略图渲染配置（72 DPI, RGB, PNG）
     *
     * @return 缩略图配置实例
     */
    public static PdfRenderOptions thumbnail() {
        return PdfRenderOptions.builder().dpi(72.0f).build();
    }

    /**
     * 创建超清印刷级渲染配置（300 DPI, RGB, PNG）
     *
     * @return 印刷级配置实例
     */
    public static PdfRenderOptions printQuality() {
        return PdfRenderOptions.builder().dpi(300.0f).build();
    }
}
