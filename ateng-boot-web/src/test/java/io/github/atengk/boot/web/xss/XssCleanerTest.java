package io.github.atengk.boot.web.xss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XSS 跨站脚本安全清洗器单元测试
 * 覆盖 ESCAPE 实体转义与 STRIP 标签剔除双轨清洗、空值边界、注释与畸形嵌套对抗
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("XssCleaner 跨站脚本清洗器单元测试")
class XssCleanerTest {

    private final XssCleaner escapeCleaner = new XssCleaner(XssMode.ESCAPE);
    private final XssCleaner stripCleaner = new XssCleaner(XssMode.STRIP);

    @Test
    @DisplayName("1. ESCAPE 模式：<script> 恶意标签转义为标准 HTML 实体")
    void shouldEscapeScriptTag() {
        String input = "<script>alert('xss')</script>";
        String output = escapeCleaner.clean(input);
        assertThat(output).isEqualTo("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
    }

    @Test
    @DisplayName("2. ESCAPE 模式：HTML 标签、双引号与 & 符号转义")
    void shouldEscapeHtmlAndSpecialCharacters() {
        String input = "<b>Bold</b> & \"Quoted\"";
        String output = escapeCleaner.clean(input);
        assertThat(output).isEqualTo("&lt;b&gt;Bold&lt;/b&gt; &amp; &quot;Quoted&quot;");
    }

    @Test
    @DisplayName("3. STRIP 模式：彻底清除 <script> 代码块及其内部恶意脚本")
    void shouldStripScriptBlockAndContent() {
        String input = "<script type=\"text/javascript\">alert('evil');</script>Normal text";
        String output = stripCleaner.clean(input);
        assertThat(output).isEqualTo("Normal text");
    }

    @Test
    @DisplayName("4. STRIP 模式：清除 <style> 样式块及其内部内容")
    void shouldStripStyleBlockAndContent() {
        String input = "<style>body { display: none; }</style>Content";
        String output = stripCleaner.clean(input);
        assertThat(output).isEqualTo("Content");
    }

    @Test
    @DisplayName("5. STRIP 模式：移除常见 HTML 标签与事件属性")
    void shouldStripHtmlTagsAndEventHandlers() {
        String input = "Hello <b class=\"title\">World</b>, <img src=x onerror=\"alert(1)\">!";
        String output = stripCleaner.clean(input);
        assertThat(output).isEqualTo("Hello World, !");
    }

    @Test
    @DisplayName("6. STRIP 模式：清除 HTML 注释")
    void shouldStripHtmlComments() {
        String input = "<!-- hidden admin info -->Normal";
        String output = stripCleaner.clean(input);
        assertThat(output).isEqualTo("Normal");
    }

    @Test
    @DisplayName("7. STRIP 模式：循环收敛清洗对抗嵌套畸形构造标签")
    void shouldStripNestedConstructedTags() {
        String input = "<scr<script>ipt>alert(1)</script>";
        String output = stripCleaner.clean(input);
        assertThat(output).doesNotContain("<script").doesNotContain("</script>");
    }

    @Test
    @DisplayName("8. STRIP 模式：保留常规数学/关系大小于比较表达式")
    void shouldPreserveMathComparison() {
        String input = "5 < 10 and 10 > 5";
        String output = stripCleaner.clean(input);
        assertThat(output).isEqualTo("5 < 10 and 10 > 5");
    }

    @Test
    @DisplayName("9. 空安全：null 与空字符串原样保底返回")
    void shouldHandleNullAndEmptyDefensively() {
        assertThat(escapeCleaner.clean(null)).isNull();
        assertThat(escapeCleaner.clean("")).isEmpty();
        assertThat(stripCleaner.clean(null)).isNull();
        assertThat(stripCleaner.clean("")).isEmpty();
    }
}
