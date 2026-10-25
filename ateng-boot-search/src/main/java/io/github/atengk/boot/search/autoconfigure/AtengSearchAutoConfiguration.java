package io.github.atengk.boot.search.autoconfigure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.search.driver.ElasticsearchClientDriver;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 搜索引擎自动装配配置类
 * <p>
 * 提供 SearchEngineClient 与 SearchTemplate 的防御性自动装配。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(prefix = "ateng.boot.search", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengSearchProperties.class)
public class AtengSearchAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SearchEngineClient.class)
    public SearchEngineClient searchEngineClient(AtengSearchProperties properties,
                                                 ObjectProvider<ObjectMapper> objectMapperProvider) {
        log.info("Auto-configuring SearchEngineClient with engine: {}", properties.getEngine());
        return new ElasticsearchClientDriver(properties, objectMapperProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(SearchTemplate.class)
    public SearchTemplate searchTemplate(SearchEngineClient searchEngineClient) {
        log.info("Auto-configuring SearchTemplate");
        return new SearchTemplate(searchEngineClient);
    }
}
