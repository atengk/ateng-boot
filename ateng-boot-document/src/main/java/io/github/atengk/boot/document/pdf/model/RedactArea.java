package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 指定物理坐标物理涂黑脱敏区域定义（左下角物理坐标系）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedactArea {

    /**
     * 目标页面索引（0-based，支持负数倒数索引如 -1 表示最后一页）
     */
    private int pageIndex;

    /**
     * 区域左下角物理坐标 X（单位：pt）
     */
    private float x;

    /**
     * 区域左下角物理坐标 Y（单位：pt）
     */
    private float y;

    /**
     * 区域物理宽度（单位：pt）
     */
    private float width;

    /**
     * 区域物理高度（单位：pt）
     */
    private float height;
}
