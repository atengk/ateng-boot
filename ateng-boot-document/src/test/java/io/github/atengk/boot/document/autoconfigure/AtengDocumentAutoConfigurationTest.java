package io.github.atengk.boot.document.autoconfigure;

import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.spi.DefaultDocumentConverter;
import io.github.atengk.boot.document.spi.DocumentConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

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
                });
    }

    @Test
    @DisplayName("测试用户自定义 DocumentConverter 时缺省 Bean 平滑让位")
    void testCustomConverterOverride() {
        contextRunner.withUserConfiguration(CustomConverterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentConverter.class);
                    assertThat(context.getBean(DocumentConverter.class)).isInstanceOf(CustomDocumentConverter.class);
                    assertThat(context).hasSingleBean(DocumentFontResolver.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 DocumentFontResolver 时缺省 Bean 平滑让位")
    void testCustomFontResolverOverride() {
        contextRunner.withUserConfiguration(CustomFontResolverConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentFontResolver.class);
                    assertThat(context.getBean(DocumentFontResolver.class)).isInstanceOf(CustomDocumentFontResolver.class);
                });
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
