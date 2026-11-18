package io.github.atengk.boot.document.autoconfigure;

import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.WordRenderException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.spi.DefaultDocumentConverter;
import io.github.atengk.boot.document.spi.DocumentConverter;
import io.github.atengk.boot.document.spi.GotenbergDocumentConverter;
import io.github.atengk.boot.document.word.WordTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文档治理自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("文档治理自动装配单元测试")
class AtengDocumentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengDocumentAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下成功装配全部组件与缺省 SPI")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DocumentProperties.class);
            assertThat(context).hasSingleBean(DocumentConverter.class);
            assertThat(context.getBean(DocumentConverter.class)).isInstanceOf(DefaultDocumentConverter.class);
            assertThat(context).hasSingleBean(DocumentFontResolver.class);
            assertThat(context).hasSingleBean(WordTemplate.class);
            assertThat(context).hasSingleBean(PdfTemplate.class);
        });
    }

    @Test
    @DisplayName("测试当 enabled=false 时完全关闭装配")
    void testDisabledConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.document.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DocumentProperties.class);
                    assertThat(context).doesNotHaveBean(DocumentConverter.class);
                    assertThat(context).doesNotHaveBean(DocumentFontResolver.class);
                    assertThat(context).doesNotHaveBean(WordTemplate.class);
                    assertThat(context).doesNotHaveBean(PdfTemplate.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 DocumentConverter 时缺省 Bean 平滑让位且自动桥接至 WordTemplate")
    void testCustomConverterOverride() {
        contextRunner.withUserConfiguration(CustomConverterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentConverter.class);
                    assertThat(context.getBean(DocumentConverter.class)).isInstanceOf(CustomDocumentConverter.class);
                    assertThat(context).hasSingleBean(DocumentFontResolver.class);
                    assertThat(context).hasSingleBean(WordTemplate.class);
                    assertThat(context).hasSingleBean(PdfTemplate.class);
                    assertThat(context.getBean(WordTemplate.class)
                            .renderToPdfToBytes("classpath:templates/word/contract-template.docx", java.util.Map.of()))
                            .isEqualTo(new byte[]{1, 2, 3});
                });
    }

    @Test
    @DisplayName("测试用户自定义 DocumentFontResolver 时缺省 Bean 平滑让位")
    void testCustomFontResolverOverride() {
        contextRunner.withUserConfiguration(CustomFontResolverConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentFontResolver.class);
                    assertThat(context.getBean(DocumentFontResolver.class)).isInstanceOf(CustomDocumentFontResolver.class);
                    assertThat(context).hasSingleBean(WordTemplate.class);
                    assertThat(context).hasSingleBean(PdfTemplate.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 WordTemplate 时缺省 Bean 平滑让位")
    void testCustomWordTemplateOverride() {
        contextRunner.withUserConfiguration(CustomWordTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(WordTemplate.class);
                    assertThat(context.getBean(WordTemplate.class)).isInstanceOf(CustomWordTemplate.class);
                    assertThat(context).hasSingleBean(PdfTemplate.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 PdfTemplate 时缺省 Bean 平滑让位")
    void testCustomPdfTemplateOverride() {
        contextRunner.withUserConfiguration(CustomPdfTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PdfTemplate.class);
                    assertThat(context.getBean(PdfTemplate.class)).isInstanceOf(CustomPdfTemplate.class);
                });
    }

    @Test
    @DisplayName("测试开启 Gotenberg 开关且配置 baseUrl 时激活 GotenbergDocumentConverter 并替代默认实现")
    void testGotenbergAutoConfigurationActivated() {
        contextRunner.withPropertyValues(
                        "ateng.boot.document.converter.gotenberg.enabled=true",
                        "ateng.boot.document.converter.gotenberg.base-url=http://localhost:3000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DocumentConverter.class);
                    assertThat(context.getBean(DocumentConverter.class)).isInstanceOf(GotenbergDocumentConverter.class);
                });
    }

    @Test
    @DisplayName("测试开启 Gotenberg 开关但缺少 baseUrl 时装配快速失败")
    void testGotenbergAutoConfigurationFailsWhenBaseUrlBlank() {
        contextRunner.withPropertyValues("ateng.boot.document.converter.gotenberg.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("测试当类路径缺少 RestClient 时即使开启 Gotenberg 也平滑回退至 DefaultDocumentConverter")
    void testGotenbergConfigurationIsolatedWithoutRestClient() {
        contextRunner.withClassLoader(new FilteredClassLoader(RestClient.class))
                .withPropertyValues(
                        "ateng.boot.document.converter.gotenberg.enabled=true",
                        "ateng.boot.document.converter.gotenberg.base-url=http://localhost:3000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DocumentConverter.class);
                    assertThat(context.getBean(DocumentConverter.class)).isInstanceOf(DefaultDocumentConverter.class);
                });
    }

    @Test
    @DisplayName("测试自动装配的 WordTemplate 继承全局 maxDocumentSize 防线")
    void testAutoConfiguredWordTemplateInheritsMaxDocumentSize() {
        contextRunner.withPropertyValues("ateng.boot.document.max-document-size=32B")
                .run(context -> {
                    WordTemplate wordTemplate = context.getBean(WordTemplate.class);
                    byte[] oversized = new byte[128];
                    assertThatThrownBy(() -> wordTemplate.extractText(new ByteArrayInputStream(oversized)))
                            .isInstanceOf(WordRenderException.class)
                            .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_SIZE_EXCEEDED);
                });
    }

    @Configuration
    static class CustomPdfTemplateConfiguration {

        @Bean
        public PdfTemplate customPdfTemplate() {
            return new CustomPdfTemplate();
        }
    }

    static class CustomPdfTemplate extends PdfTemplate {
    }

    @Configuration
    static class CustomWordTemplateConfiguration {

        @Bean
        public WordTemplate customWordTemplate() {
            return new CustomWordTemplate();
        }
    }

    static class CustomWordTemplate extends WordTemplate {
    }

    @Configuration
    static class CustomFontResolverConfiguration {

        @Bean
        public DocumentFontResolver customDocumentFontResolver() {
            return new CustomDocumentFontResolver();
        }
    }

    static class CustomDocumentFontResolver extends DocumentFontResolver {
    }

    @Configuration
    static class CustomConverterConfiguration {

        @Bean
        public DocumentConverter customDocumentConverter() {
            return new CustomDocumentConverter();
        }
    }

    static class CustomDocumentConverter implements DocumentConverter {

        @Override
        public byte[] docxToPdf(InputStream docxInputStream) {
            return new byte[]{1, 2, 3};
        }

        @Override
        public byte[] htmlToPdf(InputStream htmlInputStream) {
            return new byte[]{4, 5, 6};
        }
    }
}
