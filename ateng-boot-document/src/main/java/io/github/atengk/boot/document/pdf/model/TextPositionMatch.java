package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 关键字物理坐标定位匹配结果（统一换算为 PDF 左下角物理坐标系）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextPositionMatch {

    /**
     * 命中所在页面索引（0-based，与 {@link ImageStampOptions#getPageIndex()} 对齐）
     */
    private int pageIndex;

    /**
     * 命中的文本字符串
     */
    private String text;

    /**
     * 命中包围盒左下角物理坐标 X（单位：pt）
     */
    private float x;

    /**
     * 命中包围盒左下角物理坐标 Y（单位：pt）
     */
    private float y;

    /**
     * 命中包围盒物理宽度（单位：pt）
     */
    private float width;

    /**
     * 命中包围盒物理高度（单位：pt）
     */
    private float height;
}
