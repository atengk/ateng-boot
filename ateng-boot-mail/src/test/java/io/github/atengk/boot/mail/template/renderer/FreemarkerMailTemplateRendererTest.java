package io.github.atengk.boot.mail.template.renderer;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Freemarker 邮件模板渲染适配器测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class FreemarkerMailTemplateRendererTest {

    private FreemarkerMailTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("notice.ftl", "<h1>通知: ${title}</h1><p>${content}</p>");
        cfg.setTemplateLoader(loader);
        cfg.setDefaultEncoding("UTF-8");

        renderer = new FreemarkerMailTemplateRenderer(cfg);
    }

    @Test
    @DisplayName("测试 Freemarker 引擎渲染模板")
    void testRenderTemplate() {
        Map<String, Object> model = Map.of("title", "系统维护", "content", "今晚 23:00 执行升级");

        String html = renderer.render("notice", model);

        assertNotNull(html);
        assertTrue(html.contains("通知: 系统维护"));
        assertTrue(html.contains("今晚 23:00 执行升级"));
    }

    @Test
    @DisplayName("测试模板未找到抛出异常")
    void testTemplateNotFound() {
        assertThrows(MailSendException.class, () -> renderer.render("unknown", Map.of()));
    }
}
