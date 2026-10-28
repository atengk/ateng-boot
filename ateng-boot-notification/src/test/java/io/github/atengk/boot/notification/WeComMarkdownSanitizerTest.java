package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.WeComMarkdownSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 企业微信 Markdown 语法安全清洗器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WeComMarkdownSanitizerTest {

    @Test
    @DisplayName("验证将 Markdown 表格自动转译为企微安全的键值引用列表")
    void testTableSanitization() {
        String markdownWithTable = """
                ### 订单对账报表
                | 订单号 | 金额 | 状态 |
                | :--- | :---: | ---: |
                | ORD001 | 100.00 | 成功 |
                | ORD002 | 250.50 | 待处理 |
                
                请及时核对。
                """;

        String sanitized = WeComMarkdownSanitizer.sanitize(markdownWithTable);
        assertNotNull(sanitized);

        // 表格行必须被转译为引用列表
        assertTrue(sanitized.contains("> **订单号**: ORD001 | **金额**: 100.00 | **状态**: 成功"));
        assertTrue(sanitized.contains("> **订单号**: ORD002 | **金额**: 250.50 | **状态**: 待处理"));
        assertFalse(sanitized.contains("| :--- | :---: | ---: |"), "原分割线必须被剥离");
        assertTrue(sanitized.contains("请及时核对。"));
    }

    @Test
    @DisplayName("验证将外链图片 ![alt](url) 转译为文本链接")
    void testImageSanitization() {
        String md = "这是监控图表：\n![系统负载监控图](https://monitor.example.com/cpu.png)\n继续执行。";
        String sanitized = WeComMarkdownSanitizer.sanitize(md);

        assertFalse(sanitized.contains("![系统负载监控图]"));
        assertTrue(sanitized.contains("[图片: 系统负载监控图](https://monitor.example.com/cpu.png)"));
    }

    @Test
    @DisplayName("验证剥离非法 HTML 标签但完好保留企微原生 font 颜色标签")
    void testHtmlTagsSanitization() {
        String md = "<div><span>系统状态：</span><font color=\"info\">运行正常</font><p>详情见下方</p></div>";
        String sanitized = WeComMarkdownSanitizer.sanitize(md);

        assertFalse(sanitized.contains("<div>"));
        assertFalse(sanitized.contains("<span>"));
        assertFalse(sanitized.contains("<p>"));
        assertTrue(sanitized.contains("<font color=\"info\">运行正常</font>"), "企微原生 font color 标签必须完好保留");
    }

    @Test
    @DisplayName("验证常规代码或数学比较表达式不被误杀为 HTML 标签")
    void testMathComparisonNotStripped() {
        String md = "监控阈值警告：if (cpuUsage < 80.0 && memoryUsage > 90.0) { alert(); }";
        String sanitized = WeComMarkdownSanitizer.sanitize(md);
        assertEquals(md, sanitized, "数学或代码比较符号绝对不能被误杀");
    }

    @Test
    @DisplayName("验证带有尾部空单元格或缺省列的表格能够安全对齐")
    void testTableSanitizationWithEmptyCells() {
        String md = """
                | 列1 | 列2 | 列3 |
                |---|---|---|
                | A | B | |
                | X | |
                """;
        String sanitized = WeComMarkdownSanitizer.sanitize(md);
        assertTrue(sanitized.contains("> **列1**: A | **列2**: B | **列3**: "));
        assertTrue(sanitized.contains("> **列1**: X | **列2**:  | **列3**: "));
    }

    @Test
    @DisplayName("验证空字符串与常规 Markdown 安全直通")
    void testPlainMarkdownDirectPass() {
        assertNull(WeComMarkdownSanitizer.sanitize(null));
        assertEquals("", WeComMarkdownSanitizer.sanitize(""));

        String plain = "### 普通标题\n- 列表项 1\n- 列表项 2\n**加粗文字** 与 `行内代码`";
        assertEquals(plain, WeComMarkdownSanitizer.sanitize(plain));
    }
}
