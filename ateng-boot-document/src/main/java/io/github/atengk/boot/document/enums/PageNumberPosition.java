package io.github.atengk.boot.document.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PDF 页码压印物理位置枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum PageNumberPosition {

    /**
     * 底部居中（默认常用）
     */
    BOTTOM_CENTER("bottom-center", "底部居中"),

    /**
     * 底部靠右
     */
    BOTTOM_RIGHT("bottom-right", "底部靠右"),

    /**
     * 底部靠左
     */
    BOTTOM_LEFT("bottom-left", "底部靠左"),

    /**
     * 顶部居中
     */
    TOP_CENTER("top-center", "顶部居中"),

    /**
     * 顶部靠右
     */
    TOP_RIGHT("top-right", "顶部靠右"),

    /**
     * 顶部靠左
     */
    TOP_LEFT("top-left", "顶部靠左");

    private final String code;
    private final String desc;
}
