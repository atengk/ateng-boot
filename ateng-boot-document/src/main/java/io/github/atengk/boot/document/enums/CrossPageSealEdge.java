package io.github.atengk.boot.document.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 跨页骑缝章边缘贴合位置枚举
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Getter
@AllArgsConstructor
public enum CrossPageSealEdge {

    /**
     * 右侧边缘居中（企业合同最常用的标准骑缝章位置）
     */
    RIGHT("right", "右侧边缘"),

    /**
     * 左侧边缘居中（适用于右翻书或特定档案）
     */
    LEFT("left", "左侧边缘"),

    /**
     * 顶部边缘居中（适用于报表/表单顶部骑缝）
     */
    TOP("top", "顶部边缘"),

    /**
     * 底部边缘居中
     */
    BOTTOM("bottom", "底部边缘");

    private final String code;
    private final String desc;
}
