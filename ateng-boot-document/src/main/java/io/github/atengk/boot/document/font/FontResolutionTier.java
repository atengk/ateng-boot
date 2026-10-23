package io.github.atengk.boot.document.font;

/**
 * 字体解析来源阶梯枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
public enum FontResolutionTier {

    /**
     * 一级：外部显式配置路径 (ateng.boot.document.pdf.font-path)
     */
    CONFIG,

    /**
     * 二级：宿主机操作系统目录自适应枚举探测
     */
    SYSTEM
}
