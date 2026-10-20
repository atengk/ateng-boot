package io.github.atengk.boot.excel.enums;

/**
 * Excel 导入表头契约校验策略枚举
 *
 * @author Ateng
 * @since 2026-10-20
 */
public enum HeaderValidationStrategy {

    /**
     * 严格模式：表头列名、列数量与声明顺序必须 100% 严格一致，严禁多列、少列或列错序
     */
    STRICT,

    /**
     * 宽松模式：实体模型声明的核心列名必须在 Excel 表头中全部存在；允许列乱序，允许包含多余附加列
     */
    LOOSE,

    /**
     * 禁用模式：跳过前置表头契约校验
     */
    DISABLED
}
