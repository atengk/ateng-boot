package io.github.atengk.boot.excel.util;

/**
 * Excel 安全与公式注入防御通用工具类
 * <p>
 * 依据 OWASP 与 CWE-1236 规范，识别与对称转译/反转译潜在的 CSV/Excel 公式注入前缀（{@code =}, {@code +}, {@code -}, {@code @}, {@code \t}, {@code \r}）。
 * 导出时前置单引号强制转为静态文本，导入清洗时对称脱去保护前缀，严格守卫“内存真明文”不变量。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public final class ExcelSecurityUtil {

    private static final char SINGLE_QUOTE = '\'';

    private ExcelSecurityUtil() {
        // 工具类禁止实例化
    }

    /**
     * 检查字符串是否以潜在的公式注入特殊字符开头
     * <p>
     * 覆盖范围：{@code =}, {@code +}, {@code -}, {@code @}, {@code \t} (0x09), {@code \r} (0x0D)。
     * </p>
     *
     * @param value 待检查字符串
     * @return true 表示首字符为公式或 DDE 触发字符
     */
    public static boolean isFormulaLeading(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    /**
     * 检查字符串是否已具备单引号保护前缀
     *
     * @param value 待检查字符串
     * @return true 表示以单引号开头且后接潜在公式字符
     */
    public static boolean isAlreadyEscaped(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        return value.charAt(0) == SINGLE_QUOTE && isFormulaLeading(value.substring(1));
    }

    /**
     * 对潜在公式注入字符串执行安全转译（前置单引号）
     *
     * @param value 原始字符串
     * @return 转译后的安全文本（非公式字符或已转译文本原样返回）
     */
    public static String escapeFormulaLeading(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (isFormulaLeading(value) && !isAlreadyEscaped(value)) {
            return SINGLE_QUOTE + value;
        }
        return value;
    }

    /**
     * 对转译保护的字符串执行反向还原（脱去保护单引号）
     *
     * @param value 单元格读取文本
     * @return 还原后的真实明文字符串
     */
    public static String unescapeFormulaLeading(String value) {
        if (isAlreadyEscaped(value)) {
            return value.substring(1);
        }
        return value;
    }
}
