package io.github.atengk.boot.document.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PDF 水印图层放置模式枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum WatermarkLayer {

    /**
     * 前置覆盖模式（置于正文上方，半透明覆盖，防截图拍照与修图抹除）
     */
    OVERLAY("overlay", "前置覆盖"),

    /**
     * 底层背景模式（置于正文下方，易被实心表格底色或贴图遮挡）
     */
    UNDERLAY("underlay", "底层背景");

    private final String code;
    private final String desc;
}
