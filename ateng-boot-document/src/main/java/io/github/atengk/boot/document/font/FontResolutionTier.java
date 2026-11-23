package io.github.atengk.boot.document.font;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 字体解析来源阶梯枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum FontResolutionTier implements IBaseEnum<String> {

    /**
     * 一级：外部显式配置路径 (ateng.boot.document.pdf.font-path)
     */
    CONFIG("config", "外部显式配置"),

    /**
     * 二级：宿主机操作系统目录自适应枚举探测
     */
    SYSTEM("system", "宿主机系统目录");

    private final String code;
    private final String label;
}
