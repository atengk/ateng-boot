package io.github.atengk.boot.excel.util;

import java.math.BigDecimal;

/**
 * 字符串与目标基础类型安全转换工具
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class TypeConvertUtil {

    private TypeConvertUtil() {
    }

    /**
     * 将字符串值转换为目标类型实例
     *
     * @param value      待转换的字符串值
     * @param targetType 目标类型 Class
     * @return 转换后的对象实例；若输入为空或类型不支持则原样返回或返回 null
     */
    public static Object convertStringToTargetType(String value, Class<?> targetType) {
        if (value == null || targetType == null || targetType == String.class || targetType == Object.class) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(trimmed);
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(trimmed);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.valueOf(trimmed);
        }
        if (targetType == Byte.class || targetType == byte.class) {
            return Byte.valueOf(trimmed);
        }
        if (targetType == Short.class || targetType == short.class) {
            return Short.valueOf(trimmed);
        }
        if (targetType == Double.class || targetType == double.class) {
            return Double.valueOf(trimmed);
        }
        if (targetType == Float.class || targetType == float.class) {
            return Float.valueOf(trimmed);
        }
        if (targetType == BigDecimal.class) {
            return new BigDecimal(trimmed);
        }
        return trimmed;
    }
}
