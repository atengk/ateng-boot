package io.github.atengk.boot.web.dict.autoconfigure;

import io.github.atengk.boot.web.dict.EnumDictionaryController;
import io.github.atengk.boot.web.dict.EnumDictionaryRegistry;
import io.github.atengk.boot.web.properties.EnumDictionaryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;

/**
 * 枚举字典 Web 运行时与自动装配配置类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.web.enum", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EnumDictionaryProperties.class)
public class AtengEnumDictionaryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EnumDictionaryRegistry enumDictionaryRegistry(EnumDictionaryProperties properties,
                                                         ObjectProvider<MessageSource> messageSourceProvider) {
        return new EnumDictionaryRegistry(properties, messageSourceProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public EnumDictionaryController enumDictionaryController(EnumDictionaryRegistry registry) {
        return new EnumDictionaryController(registry);
    }
}
