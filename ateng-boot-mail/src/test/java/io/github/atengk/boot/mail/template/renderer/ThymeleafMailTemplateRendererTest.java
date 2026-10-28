package io.github.atengk.boot.mail.template.renderer;

import io.github.atengk.boot.mail.exception.MailSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thymeleaf 邮件模板渲染适配器测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class ThymeleafMailTemplateRendererTest {

    private ThymeleafMailTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode("HTML");
        engine.setTemplateResolver(resolver);

        renderer = new ThymeleafMailTemplateRenderer(engine);
    }

    @Test
    @DisplayName("测试 Thymeleaf 引擎渲染模板")
    void testRenderTemplate() {
        Map<String, Object> model = Map.of("username", "赵六", "platform", "Ateng Boot");

        String html = renderer.render("thymeleaf-welcome", model);

        assertNotNull(html);
        assertTrue(html.contains("欢迎, 赵六!"));
    }

    @Test
    @DisplayName("测试空模板名抛出异常")
    void testEmptyTemplateName() {
        assertThrows(MailSendException.class, () -> renderer.render(null, Map.of()));
    }
}
