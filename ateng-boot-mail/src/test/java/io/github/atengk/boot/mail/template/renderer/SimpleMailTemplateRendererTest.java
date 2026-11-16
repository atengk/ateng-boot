package io.github.atengk.boot.mail.template.renderer;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯原生极简正则模板渲染器测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class SimpleMailTemplateRendererTest {

    private final SimpleMailTemplateRenderer renderer = new SimpleMailTemplateRenderer();

    @Test
    @DisplayName("测试内联模板字符串变量替换")
    void testRenderInlineTemplate() {
        String template = "Hello, ${name}! Your verification code is ${code}.";
        Map<String, Object> model = Map.of("name", "张三", "code", 889900);

        String result = renderer.render(template, model);

        assertEquals("Hello, 张三! Your verification code is 889900.", result);
    }

    @Test
    @DisplayName("测试嵌套路径属性解析 (${user.profile.name})")
    void testRenderNestedProperty() {
        String template = "Welcome ${user.name}, your role is ${user.role}!";
        Map<String, Object> user = Map.of("name", "李四", "role", "ADMIN");
        Map<String, Object> model = Map.of("user", user);

        String result = renderer.render(template, model);

        assertEquals("Welcome 李四, your role is ADMIN!", result);
    }

    @Test
    @DisplayName("测试类路径模板资源读取与渲染")
    void testRenderClasspathTemplate() {
        Map<String, Object> model = Map.of("username", "王五", "platform", "Ateng Boot");

        String result = renderer.render("welcome", model);

        assertNotNull(result);
        assertTrue(result.contains("欢迎, 王五!"));
        assertTrue(result.contains("感谢您注册 Ateng Boot 平台"));
    }

    @Test
    @DisplayName("测试模板未找到时抛出 MAIL_TEMPLATE_RENDER_ERROR 异常")
    void testTemplateNotFound() {
        MailSendException ex = assertThrows(MailSendException.class, () ->
                renderer.render("not-exist-template-xyz", Map.of()));

        assertEquals(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("not-exist-template-xyz"));
    }

    @Test
    @DisplayName("测试空模板名抛出异常")
    void testEmptyTemplateName() {
        MailSendException ex = assertThrows(MailSendException.class, () ->
                renderer.render("", Map.of()));

        assertEquals(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("测试空模型原样返回模板内容")
    void testNullModel() {
        String template = "<h1>Hello World</h1>";
        assertEquals("<h1>Hello World</h1>", renderer.render(template, null));
        assertEquals("<h1>Hello World</h1>", renderer.render(template, new HashMap<>()));
    }

    @Test
    @DisplayName("测试模板名称包含 .. 或 \\0 立即抛出 MAIL_TEMPLATE_RENDER_ERROR")
    void testRejectsPathTraversalAndNullByte() {
        for (String malicious : java.util.List.of("../secret.html", "..\\secret", "welcome\0.html", "<p>${name}</p>\0")) {
            MailSendException ex = assertThrows(MailSendException.class, () ->
                    renderer.render(malicious, Map.of("name", "a")));
            assertEquals(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR, ex.getResultCode());
        }
    }

    @Test
    @DisplayName("测试默认关闭 htmlEscape 保持兼容，开启后转义 ${var} 并保留 $!{rawVar} 原文")
    void testHtmlEscapeAndRawPlaceholder() {
        String tpl = "<p>Hi ${name}</p><div>$!{rawHtml}</div>";
        Map<String, Object> model = Map.of(
                "name", "<script>alert('xss' & \"1\")</script>",
                "rawHtml", "<b>Trusted</b>"
        );

        // 默认关闭：保持向后兼容
        String defaultRendered = new SimpleMailTemplateRenderer().render(tpl, model);
        assertEquals("<p>Hi <script>alert('xss' & \"1\")</script></p><div><b>Trusted</b></div>", defaultRendered);

        // 开启 HTML 转义：${var} 自动转义危险字符，而 $!{rawVar} 保持原文
        SimpleMailTemplateRenderer escapedRenderer = new SimpleMailTemplateRenderer(true);
        String escapedRendered = escapedRenderer.render(tpl, model);
        assertEquals(
                "<p>Hi &lt;script&gt;alert(&#39;xss&#39; &amp; &quot;1&quot;)&lt;/script&gt;</p><div><b>Trusted</b></div>",
                escapedRendered
        );
    }
}
