package io.github.atengk.boot.document.properties;

import io.github.atengk.boot.document.enums.WatermarkLayer;
import lombok.Data;

/**
 * PDF 防泄密水印默认配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class DocumentWatermarkProperties {

    /**
     * 默认防泄密水印文本模版（支持动态宏变量）
     */
    private String defaultText = "#{user.nickname!''} #{now('yyyy-MM-dd')}";

    /**
     * 水印不透明度（0.0f 为完全透明，1.0f 为完全实心，推荐 0.15f ~ 0.25f）
     */
    private float opacity = 0.2f;

    /**
     * 水印文字旋转角度（度，负数表示逆时针旋转）
     */
    private float rotation = -30.0f;

    /**
     * 水印文字字号大小（单位：pt）
     */
    private float fontSize = 24.0f;

    /**
     * 水印十六进制 RGB 颜色值（例如 #888888、#D3D3D3）
     */
    private String color = "#888888";

    /**
     * 水印图层放置模式（OVERLAY: 前置覆盖防抹除 / UNDERLAY: 背景底层）
     */
    private WatermarkLayer layer = WatermarkLayer.OVERLAY;

    /**
     * 水印平铺网格行间距（单位：pt）
     */
    private float rowSpacing = 150.0f;

    /**
     * 水印平铺网格列间距（单位：pt）
     */
    private float colSpacing = 200.0f;
}
