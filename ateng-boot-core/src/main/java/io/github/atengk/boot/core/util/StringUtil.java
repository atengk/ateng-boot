package io.github.atengk.boot.core.util;

import org.springframework.util.StringUtils;

/**
 * 字符串通用处理与格式化高性能工具类
 * 基于纯原生 JDK 17 与 Spring 原生能力实现，提供无正则零回溯占位符格式化、空安全判断与驼峰下划线双向转换
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class StringUtil {

    private static final String EMPTY = "";
    private static final String PLACEHOLDER = "{}";

    private StringUtil() {
    }

    /**
     * 判断字符串是否包含有效文本（非 null、非空且包含至少一个非空白字符）
     *
     * @param str 待检查的字符序列
     * @return 若包含有效文本返回 true，否则返回 false
     */
    public static boolean hasText(CharSequence str) {
        return StringUtils.hasText(str);
    }

    /**
     * 判断字符串是否为空（null 或长度为 0）
     *
     * @param str 待检查的字符序列
     * @return 若为 null 或空串返回 true，否则返回 false
     */
    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    /**
     * 判断字符串是否非空（非 null 且长度大于 0）
     *
     * @param str 待检查的字符序列
     * @return 若非空返回 true，否则返回 false
     */
    public static boolean isNotEmpty(CharSequence str) {
        return !isEmpty(str);
    }

    /**
     * 判断字符串是否为空白（null、空串或仅包含空白字符）
     *
     * @param str 待检查的字符序列
     * @return 若为空白返回 true，否则返回 false
     */
    public static boolean isBlank(CharSequence str) {
        return !hasText(str);
    }

    /**
     * 判断字符串是否为非空白（包含至少一个非空白字符）
     *
     * @param str 待检查的字符序列
     * @return 若非空白返回 true，否则返回 false
     */
    public static boolean isNotBlank(CharSequence str) {
        return hasText(str);
    }

    /**
     * 高性能字符串模板格式化（基于 "{}" 占位符顺序替换，零正则表达式，杜绝 ReDoS 风险）
     *
     * @param template 待格式化模板字符串（如 "Hello {}, welcome to {}"）
     * @param args     占位符参数数组
     * @return 格式化后的完整字符串；若 template 为 null 则返回 null
     */
    public static String format(String template, Object... args) {
        if (template == null) {
            return null;
        }
        if (args == null || args.length == 0 || !template.contains(PLACEHOLDER)) {
            return template;
        }

        StringBuilder sb = new StringBuilder(template.length() + (args.length * 16));
        int cursor = 0;
        int argIndex = 0;
        int placeholderIndex;

        while ((placeholderIndex = template.indexOf(PLACEHOLDER, cursor)) != -1) {
            // 检查转义符前缀（如 "\\{}"）
            if (placeholderIndex > 0 && template.charAt(placeholderIndex - 1) == '\\') {
                if (placeholderIndex > 1 && template.charAt(placeholderIndex - 2) == '\\') {
                    // 转义反斜杠："\\\\{}" -> 输出一个 "\" 并继续替换 "{}"
                    sb.append(template, cursor, placeholderIndex - 1);
                    if (argIndex < args.length) {
                        sb.append(args[argIndex++]);
                    } else {
                        sb.append(PLACEHOLDER);
                    }
                    cursor = placeholderIndex + 2;
                } else {
                    // 转义占位符："\\{}" -> 输出 "{}" 占位符字面量，不消耗参数
                    sb.append(template, cursor, placeholderIndex - 1);
                    sb.append(PLACEHOLDER);
                    cursor = placeholderIndex + 2;
                }
            } else {
                // 正常替换 "{}"
                sb.append(template, cursor, placeholderIndex);
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append(PLACEHOLDER);
                }
                cursor = placeholderIndex + 2;
            }
        }

        sb.append(template.substring(cursor));
        return sb.toString();
    }

    /**
     * 将下划线命名字符串转换为小驼峰命名（如 "user_order_id" -> "userOrderId"）
     *
     * @param str 待转换的原始下划线字符串
     * @return 转换后的小驼峰字符串；若为 null 或空串返回自身
     */
    public static String toCamelCase(String str) {
        if (!hasText(str)) {
            return str;
        }
        if (!str.contains("_")) {
            // 无下划线时，若首字符大写则转小写
            return Character.isUpperCase(str.charAt(0))
                    ? Character.toLowerCase(str.charAt(0)) + str.substring(1)
                    : str;
        }

        StringBuilder sb = new StringBuilder(str.length());
        boolean upperNext = false;
        int len = str.length();

        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else {
                if (sb.isEmpty()) {
                    sb.append(Character.toLowerCase(c));
                    upperNext = false;
                } else if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 将驼峰命名字符串转换为小写下划线命名（如 "userOrderId" -> "user_order_id"）
     *
     * @param str 待转换的驼峰字符串
     * @return 转换后的下划线小写字符串；若为 null 或空串返回自身
     */
    public static String toUnderlineCase(String str) {
        if (!hasText(str)) {
            return str;
        }

        int len = str.length();
        StringBuilder sb = new StringBuilder(len + 4);

        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                // 如果前一个字符不是下划线，且非首字符，追加下划线
                if (i > 0 && str.charAt(i - 1) != '_' && !Character.isUpperCase(str.charAt(i - 1))) {
                    sb.append('_');
                } else if (i > 0 && str.charAt(i - 1) != '_' && i + 1 < len && Character.isLowerCase(str.charAt(i + 1))) {
                    // 处理连续大写后跟小写（如 "HTTPRequest" -> "http_request"）
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将驼峰命名字符串转换为小写下划线命名（{@link #toUnderlineCase(String)} 规范别名）
     *
     * @param str 待转换的驼峰字符串
     * @return 转换后的下划线字符串
     */
    public static String toSnakeCase(String str) {
        return toUnderlineCase(str);
    }
}
