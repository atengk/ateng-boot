package io.github.atengk.boot.excel.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExcelSecurityUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
class ExcelSecurityUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "=SUM(A1:B1)",
            "=cmd|'/C calc'!A0",
            "+1234567890",
            "+SUM(1,2)",
            "-1000",
            "-cmd|'/C calc'!A0",
            "@username",
            "\tcommand",
            "\rcommand"
    })
    @DisplayName("测试公式敏感前缀识别")
    void testIsFormulaLeading(String input) {
        assertTrue(ExcelSecurityUtil.isFormulaLeading(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "normal text",
            "张三",
            "123456",
            "user@example.com", // @ is not at index 0
            "",
            "   "
    })
    @DisplayName("测试常规文本不被误判为公式前缀")
    void testIsFormulaLeadingFalse(String input) {
        assertFalse(ExcelSecurityUtil.isFormulaLeading(input));
    }

    @Test
    @DisplayName("测试空值与极端边界判空")
    void testNullAndEmpty() {
        assertFalse(ExcelSecurityUtil.isFormulaLeading(null));
        assertFalse(ExcelSecurityUtil.isFormulaLeading(""));
        assertNull(ExcelSecurityUtil.escapeFormulaLeading(null));
        assertEquals("", ExcelSecurityUtil.escapeFormulaLeading(""));
        assertNull(ExcelSecurityUtil.unescapeFormulaLeading(null));
        assertEquals("", ExcelSecurityUtil.unescapeFormulaLeading(""));
    }

    @Test
    @DisplayName("测试转译防注入与防二次转译")
    void testEscapeFormulaLeading() {
        assertEquals("'=SUM(A1)", ExcelSecurityUtil.escapeFormulaLeading("=SUM(A1)"));
        assertEquals("'+8613800000000", ExcelSecurityUtil.escapeFormulaLeading("+8613800000000"));
        assertEquals("'-500", ExcelSecurityUtil.escapeFormulaLeading("-500"));
        assertEquals("'@Admin", ExcelSecurityUtil.escapeFormulaLeading("@Admin"));
        assertEquals("'\tcmd", ExcelSecurityUtil.escapeFormulaLeading("\tcmd"));
        assertEquals("'\rcmd", ExcelSecurityUtil.escapeFormulaLeading("\rcmd"));

        // 防二次转译
        assertEquals("'=SUM(A1)", ExcelSecurityUtil.escapeFormulaLeading("'=SUM(A1)"));

        // 普通文本不加单引号
        assertEquals("hello world", ExcelSecurityUtil.escapeFormulaLeading("hello world"));
        assertEquals("'hello'", ExcelSecurityUtil.escapeFormulaLeading("'hello'"));
    }

    @Test
    @DisplayName("测试对称反向还原明文")
    void testUnescapeFormulaLeading() {
        assertEquals("=SUM(A1)", ExcelSecurityUtil.unescapeFormulaLeading("'=SUM(A1)"));
        assertEquals("+8613800000000", ExcelSecurityUtil.unescapeFormulaLeading("'+8613800000000"));
        assertEquals("-500", ExcelSecurityUtil.unescapeFormulaLeading("'-500"));
        assertEquals("@Admin", ExcelSecurityUtil.unescapeFormulaLeading("'@Admin"));
        assertEquals("\tcmd", ExcelSecurityUtil.unescapeFormulaLeading("'\tcmd"));
        assertEquals("\rcmd", ExcelSecurityUtil.unescapeFormulaLeading("'\rcmd"));

        // 普通单引号不被误删
        assertEquals("'hello'", ExcelSecurityUtil.unescapeFormulaLeading("'hello'"));
        assertEquals("normal text", ExcelSecurityUtil.unescapeFormulaLeading("normal text"));
    }
}
