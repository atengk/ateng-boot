package io.github.atengk.boot.mail.template.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件模板渲染引擎国际化多语言级联解析单测
 *
 * @author Ateng
 * @since 2026-10-30
 */
class MailTemplateRendererI18nTest {

    @Test
    @DisplayName("SimpleMailTemplateRenderer: 精确匹配语言+国家变体 (welcome_en_US.html)")
    void testSimpleRendererExactLanguageAndCountry() {
        SimpleMailTemplateRenderer renderer = new SimpleMailTemplateRenderer();
        Map<String, Object> model = Map.of("username", "Alice", "platform", "Ateng Cloud");

        String result = renderer.render("welcome", model, Locale.US);

        assertThat(result)
                .contains("Welcome US, Alice!")
                .contains("Thank you for joining Ateng Cloud in US!");
    }

    @Test
    @DisplayName("SimpleMailTemplateRenderer: 级联降级至纯语言变体 (welcome_en.html)")
    void testSimpleRendererCascadeLanguageFallback() {
        SimpleMailTemplateRenderer renderer = new SimpleMailTemplateRenderer();
        Map<String, Object> model = Map.of("username", "Bob", "platform", "Ateng Cloud");

        // 传入英国英语 (en_GB)，由于无 welcome_en_GB.html，级联降级至 welcome_en.html
        String result = renderer.render("welcome", model, Locale.UK);

        assertThat(result)
                .contains("Welcome EN, Bob!")
                .contains("Thank you for joining Ateng Cloud!");
    }

    @Test
    @DisplayName("SimpleMailTemplateRenderer: 无任何匹配语言时最终回退至基础缺省模板 (welcome.html)")
    void testSimpleRendererCascadeDefaultFallback() {
        SimpleMailTemplateRenderer renderer = new SimpleMailTemplateRenderer();
        Map<String, Object> model = Map.of("username", "Charlie", "platform", "Ateng Cloud");

        // 传入法语，因无对应文件，回退至缺省模板
        String result = renderer.render("welcome", model, Locale.FRANCE);

        assertThat(result)
                .contains("欢迎, Charlie!")
                .contains("感谢您注册 Ateng Cloud 平台");
    }

    @Test
    @DisplayName("SimpleMailTemplateRenderer: 纯内联模板不受国际化文件查找影响直接插值")
    void testSimpleRendererInlineTemplate() {
        SimpleMailTemplateRenderer renderer = new SimpleMailTemplateRenderer();
        Map<String, Object> model = Map.of("title", "系统告警", "code", 500);

        String result = renderer.render("<h3>${title}</h3><p>错误码: ${code}</p>", model, Locale.GERMANY);

        assertThat(result).isEqualTo("<h3>系统告警</h3><p>错误码: 500</p>");
    }

    @Test
    @DisplayName("ThymeleafMailTemplateRenderer: 接入 Locale 正常渲染模板")
    void testThymeleafRendererWithLocale() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        ThymeleafMailTemplateRenderer renderer = new ThymeleafMailTemplateRenderer(engine);
        Map<String, Object> model = Map.of("username", "David", "platform", "Ateng Cloud");

        String result = renderer.render("thymeleaf-welcome", model, Locale.SIMPLIFIED_CHINESE);

        assertThat(result)
                .contains("David")
                .contains("Ateng Cloud");
    }
}
