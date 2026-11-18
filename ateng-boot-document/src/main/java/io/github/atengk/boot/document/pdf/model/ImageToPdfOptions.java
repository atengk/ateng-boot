package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多图合并编排导出 PDF 配置选项（支持标准页面等比适配与按原图尺寸精确封装）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageToPdfOptions {

    /**
     * 是否按每张输入图片的原始像素尺寸自动撑开单页 PDF 宽高（默认 false，使用标准 A4/自定义纸张）
     */
    @Builder.Default
    private boolean fitToImage = false;

    /**
     * 标准页面宽度（单位：pt，仅当 fitToImage 为 false 时生效，默认 A4 宽度 595.27563f）
     */
    @Builder.Default
    private float pageWidth = 595.27563f;

    /**
     * 标准页面高度（单位：pt，仅当 fitToImage 为 false 时生效，默认 A4 高度 841.8898f）
     */
    @Builder.Default
    private float pageHeight = 841.8898f;

    /**
     * 页面上边距（单位：pt，默认 20.0f）
     */
    @Builder.Default
    private float marginTop = 20.0f;

    /**
     * 页面下边距（单位：pt，默认 20.0f）
     */
    @Builder.Default
    private float marginBottom = 20.0f;

    /**
     * 页面左边距（单位：pt，默认 20.0f）
     */
    @Builder.Default
    private float marginLeft = 20.0f;

    /**
     * 页面右边距（单位：pt，默认 20.0f）
     */
    @Builder.Default
    private float marginRight = 20.0f;

    /**
     * 标准页面模式下是否保持图片原始宽高比自适应缩放（默认 true）
     */
    @Builder.Default
    private boolean keepAspectRatio = true;

    /**
     * 标准页面模式下是否水平垂直居中绘制图片（默认 true）
     */
    @Builder.Default
    private boolean center = true;

    /**
     * 创建默认标准 A4 自适应编排配置
     *
     * @return 默认 ImageToPdfOptions 实例
     */
    public static ImageToPdfOptions defaults() {
        return ImageToPdfOptions.builder().build();
    }

    /**
     * 创建按每张图片原始像素尺寸零边距精确成页的配置
     *
     * @return fitToImage=true 的配置实例
     */
    public static ImageToPdfOptions fitToImage() {
        return ImageToPdfOptions.builder()
                .fitToImage(true)
                .marginTop(0.0f)
                .marginBottom(0.0f)
                .marginLeft(0.0f)
                .marginRight(0.0f)
                .build();
    }
}
