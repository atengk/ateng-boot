package io.github.atengk.boot.mail.template.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件模板资源与国际化级联路径工具单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
class MailTemplateResourceUtilTest {

    @Test
    @DisplayName("buildLocaleCandidateNames: 语言与国家全量匹配级联降级候选生成")
    void testBuildLocaleCandidateNamesWithLanguageAndCountry() {
        List<String> candidates = MailTemplateResourceUtil.buildLocaleCandidateNames("welcome.html", Locale.US);

        assertThat(candidates).containsExactly(
                "welcome_en_US.html",
                "welcome_en.html",
                "welcome.html"
        );
    }

    @Test
    @DisplayName("buildLocaleCandidateNames: 无扩展名基础名称保持逻辑名称对称性")
    void testBuildLocaleCandidateNamesWithoutExtension() {
        List<String> candidates = MailTemplateResourceUtil.buildLocaleCandidateNames("welcome", Locale.US);

        assertThat(candidates).containsExactly(
                "welcome_en_US",
                "welcome_en",
                "welcome"
        );
    }

    @Test
    @DisplayName("buildPhysicalCandidateFileNames: 强制生成合法的物理文件扩展名候选")
    void testBuildPhysicalCandidateFileNames() {
        List<String> candidates = MailTemplateResourceUtil.buildPhysicalCandidateFileNames("welcome", Locale.US, ".html");

        assertThat(candidates).contains(
                "welcome_en_US.html",
                "welcome_en.html",
                "welcome.html"
        );
    }

    @Test
    @DisplayName("buildLocaleCandidateNames: 仅指定语言时生成语言变体与保底")
    void testBuildLocaleCandidateNamesLanguageOnly() {
        List<String> candidates = MailTemplateResourceUtil.buildLocaleCandidateNames("notice.html", Locale.ENGLISH);

        assertThat(candidates).containsExactly(
                "notice_en.html",
                "notice.html"
        );
    }

    @Test
    @DisplayName("buildLocaleCandidateNames: 空入参或 null Locale 防御保底")
    void testBuildLocaleCandidateNamesEdgeCases() {
        assertThat(MailTemplateResourceUtil.buildLocaleCandidateNames(null, Locale.US)).isEmpty();
        assertThat(MailTemplateResourceUtil.buildLocaleCandidateNames("   ", Locale.US)).isEmpty();

        List<String> noLocale = MailTemplateResourceUtil.buildLocaleCandidateNames("template.html", null);
        assertThat(noLocale).containsExactly("template.html");
    }

    @Test
    @DisplayName("resolveClasspathResource & existsInClasspath: 成功探测类路径模板资源")
    void testResolveClasspathResource() {
        // test/resources 下存在 templates/mail/welcome_en.html 与 welcome_en_US.html
        Resource resource = MailTemplateResourceUtil.resolveClasspathResource("welcome_en.html");
        assertThat(resource).isNotNull();
        assertThat(resource.exists()).isTrue();

        assertThat(MailTemplateResourceUtil.existsInClasspath("welcome_en.html")).isTrue();
        assertThat(MailTemplateResourceUtil.existsInClasspath("non_existent_template_xyz.html")).isFalse();
        assertThat(MailTemplateResourceUtil.existsInClasspath(null)).isFalse();
    }

    @Test
    @DisplayName("resolveExistingTemplateName: 级联匹配存在的最佳物理模板文件名")
    void testResolveExistingTemplateName() {
        // 匹配到 welcome_en_US.html
        String resolvedUs = MailTemplateResourceUtil.resolveExistingTemplateName("welcome.html", Locale.US);
        assertThat(resolvedUs).isEqualTo("welcome_en_US.html");

        // 英国英语 (en_GB) 级联回退到 welcome_en.html
        String resolvedGb = MailTemplateResourceUtil.resolveExistingTemplateName("welcome.html", Locale.UK);
        assertThat(resolvedGb).isEqualTo("welcome_en.html");

        // 不存在任何语言变体时保底返回原始 templateName
        String fallback = MailTemplateResourceUtil.resolveExistingTemplateName("not_exist.html", Locale.GERMAN);
        assertThat(fallback).isEqualTo("not_exist.html");

        // null 防御
        assertThat(MailTemplateResourceUtil.resolveExistingTemplateName("welcome.html", null)).isEqualTo("welcome.html");
        assertThat(MailTemplateResourceUtil.resolveExistingTemplateName(null, Locale.US)).isNull();
    }
}
