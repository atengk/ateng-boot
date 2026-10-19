package io.github.atengk.boot.mybatisplus.dynamicquery;

/**
 * 动态查询受控安全操作符枚举
 * 严格收敛 13 种标准比较与模式匹配操作符，杜绝非法 SQL 运算符注入
 *
 * @author Ateng
 * @since 2026-10-19
 */
public enum QueryOperator {

    /**
     * 等于 (=)
     */
    EQ,

    /**
     * 不等于 (<>)
     */
    NE,

    /**
     * 大于 (>)
     */
    GT,

    /**
     * 大于等于 (>=)
     */
    GE,

    /**
     * 小于 (<)
     */
    LT,

    /**
     * 小于等于 (<=)
     */
    LE,

    /**
     * 全模糊匹配 (%value%)
     */
    LIKE,

    /**
     * 左模糊匹配 (%value)
     */
    LIKE_LEFT,

    /**
     * 右模糊匹配 (value%)
     */
    LIKE_RIGHT,

    /**
     * 集合包含 (IN (...))
     */
    IN,

    /**
     * 集合不包含 (NOT IN (...))
     */
    NOT_IN,

    /**
     * 闭区间范围 (BETWEEN val1 AND val2)
     */
    BETWEEN,

    /**
     * 为空 (IS NULL)
     */
    IS_NULL,

    /**
     * 非空 (IS NOT NULL)
     */
    IS_NOT_NULL;

    /**
     * 从字符串安全解析操作符（忽略大小写、首尾空格及中划线/下划线）
     *
     * @param value 操作符字符串表达式（如 "eq", "LIKE_LEFT", "like-left"）
     * @return 对应的 QueryOperator 枚举
     * @throws IllegalArgumentException 当传入非法或未受控的操作符时抛出
     */
    public static QueryOperator fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return EQ;
        }
        String clean = value.trim().replace("-", "").replace("_", "").toUpperCase(java.util.Locale.ROOT);
        for (QueryOperator operator : values()) {
            String opClean = operator.name().replace("_", "");
            if (opClean.equals(clean)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("未受控的动态查询操作符: " + value + "，仅支持 13 种受控白名单操作符");
    }

    /**
     * 判断当前操作符是否为多值参数类型（IN, NOT_IN, BETWEEN）
     *
     * @return true 若为多值类型；否则 false
     */
    public boolean isMultiValue() {
        return this == IN || this == NOT_IN || this == BETWEEN;
    }

    /**
     * 判断当前操作符是否为无值参数类型（IS_NULL, IS_NOT_NULL）
     *
     * @return true 若无需参数值；否则 false
     */
    public boolean isNoValue() {
        return this == IS_NULL || this == IS_NOT_NULL;
    }
}
