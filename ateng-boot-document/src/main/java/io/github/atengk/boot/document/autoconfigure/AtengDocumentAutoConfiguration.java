package io.github.atengk.boot.document.autoconfigure;

import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.spi.DefaultDocumentConverter;
import io.github.atengk.boot.document.spi.DocumentConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}
