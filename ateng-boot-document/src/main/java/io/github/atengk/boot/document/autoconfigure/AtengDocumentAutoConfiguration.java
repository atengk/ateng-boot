package io.github.atengk.boot.document.autoconfigure;

import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.spi.DefaultDocumentConverter;
import io.github.atengk.boot.document.spi.DocumentConverter;
import io.github.atengk.boot.document.spi.GotenbergDocumentConverter;
import io.github.atengk.boot.document.word.WordTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 企业级文档治理底座自动装配器
 *
 * @author Ateng
 * @since 2026-10-23
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ateng.boot.document", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class AtengDocumentAutoConfiguration {

    /**
     * Gotenberg 远程跨格式转换适配器条件装配配置（独立隔离 {@link RestClient} 类加载边界）
     *
     * @author Ateng
     * @since 2026-11-18
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnProperty(prefix = "ateng.boot.document.converter.gotenberg", name = "enabled", havingValue = "true")
    static class GotenbergConverterConfiguration {

        /**
         * 注册 Gotenberg 远程文档转换器 SPI
         *
         * @param properties 文档治理根配置属性
         * @return GotenbergDocumentConverter 实例
         */
        @Bean
        @ConditionalOnMissingBean(DocumentConverter.class)
        public DocumentConverter gotenbergDocumentConverter(DocumentProperties properties) {
            return new GotenbergDocumentConverter(properties);
        }
    }

    /**
     * 注册缺省文档跨格式转换器 SPI（保底快速失败）
     *
     * @return DocumentConverter SPI 实例
     */
    @Bean
    @ConditionalOnMissingBean(DocumentConverter.class)
    public DocumentConverter documentConverter() {
        return new DefaultDocumentConverter();
    }

    /**
     * 注册 CJK 中文字体云原生三级弹性探测解析器
     *
     * @param properties 文档治理根配置属性
     * @return DocumentFontResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean(DocumentFontResolver.class)
    public DocumentFontResolver documentFontResolver(DocumentProperties properties) {
        return new DocumentFontResolver(properties.getPdf());
    }

    /**
     * 注册 Word 文档模板渲染统一操作门面
     *
     * @param properties 文档治理根配置属性
     * @return WordTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(WordTemplate.class)
    public WordTemplate wordTemplate(DocumentProperties properties) {
        return new WordTemplate(properties);
    }

    /**
     * 注册 PDF 文档治理统一操作门面
     *
     * @param properties   文档治理根配置属性
     * @param fontResolver 中文字体解析器
     * @return PdfTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(PdfTemplate.class)
    public PdfTemplate pdfTemplate(DocumentProperties properties, DocumentFontResolver fontResolver) {
        return new PdfTemplate(properties, fontResolver);
    }
}
