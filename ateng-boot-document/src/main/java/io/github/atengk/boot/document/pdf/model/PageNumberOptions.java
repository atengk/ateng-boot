package io.github.atengk.boot.document.pdf.model;

import io.github.atengk.boot.document.enums.PageNumberPosition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 自定义页码压印高级选项
 * <p>
 * 支持自定义页码格式（如“第 {page} 页 / 共 {total} 页”）、原生跳过封面与前置目录页（skipPages）、
 * 重新计算总页数及指定物理位置与样式。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageNumberOptions {

    /**
     * 页码文本模板格式（支持 {page} 与 {total} 占位符，默认: "第 {page} 页 / 共 {total} 页"）
     */
    @Builder.Default
    private String format = "第 {page} 页 / 共 {total} 页";

    /**
     * 跳过前置压印的页面总数（例如 1 表示跳过封面第 1 页，2 表示跳过封面与目录前 2 页，默认: 0）
     */
    @Builder.Default
    private int skipPages = 0;

    /**
     * 起始页码编号（跳过封面后第一张有效页面显示的页号，默认: 1）
     */
    @Builder.Default
    private int startNumber = 1;

    /**
     * 是否在总页数中扣除已跳过的页面数量（默认: true）
     * 例如 10 页文档跳过 1 页封面：
     * true 则展示为“第 1 页 / 共 9 页”；
     * false 则展示为“第 1 页 / 共 10 页”。
     */
    @Builder.Default
    private boolean recalculateTotal = true;

    /**
     * 页码压印物理位置（默认: 底部居中 BOTTOM_CENTER）
     */
    @Builder.Default
    private PageNumberPosition position = PageNumberPosition.BOTTOM_CENTER;

    /**
     * 页码文字字号大小（单位：pt，默认: 10.0f）
     */
    @Builder.Default
    private float fontSize = 10.0f;

    /**
     * 页码十六进制 RGB 颜色值（例如 #666666、#333333，默认: #666666）
     */
    @Builder.Default
    private String color = "#666666";

    /**
     * 距离页面对应边缘的绝对物理外边距（单位：pt，默认: 30.0f）
     */
    @Builder.Default
    private float margin = 30.0f;

    /**
     * 自定义页码中文字体路径（可选，若为空则由 DocumentFontResolver 自动探测）
     */
    private String customFontPath;

    /**
     * 快速构建跳过指定封面页数的默认页码选项
     *
     * @param skipPages 跳过的封面与目录页数
     * @return PageNumberOptions 实例
     */
    public static PageNumberOptions skipCover(int skipPages) {
        return PageNumberOptions.builder()
                .skipPages(Math.max(0, skipPages))
                .build();
    }

    /**
     * 快速构建指定格式的默认页码选项
     *
     * @param format 页码模版格式（如 "{page} / {total}"）
     * @return PageNumberOptions 实例
     */
    public static PageNumberOptions ofFormat(String format) {
        return PageNumberOptions.builder()
                .format(format)
                .build();
    }
}
