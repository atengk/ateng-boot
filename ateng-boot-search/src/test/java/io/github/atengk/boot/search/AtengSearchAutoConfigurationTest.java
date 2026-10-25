package io.github.atengk.boot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.atengk.boot.search.autoconfigure.AtengSearchAutoConfiguration;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.template.SearchTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索自动装配与条件守卫测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class AtengSearchAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSearchAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下 SearchEngineClient 与 SearchTemplate 成功自动装配")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SearchEngineClient.class);
            assertThat(context).hasSingleBean(SearchTemplate.class);
        });
    }

    @Test
    @DisplayName("测试配置 enabled=false 时关闭自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.search.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SearchEngineClient.class);
                    assertThat(context).doesNotHaveBean(SearchTemplate.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 Bean 时 @ConditionalOnMissingBean 平滑接管")
    void testCustomBeanTakesPrecedence() {
        contextRunner
                .withUserConfiguration(CustomSearchConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SearchEngineClient.class);
                    SearchEngineClient client = context.getBean(SearchEngineClient.class);
                    assertThat(client.getEngineType()).isEqualTo(SearchEngineType.OPENSEARCH);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSearchConfiguration {

        @Bean
        public SearchEngineClient customSearchEngineClient() {
            return new SearchEngineClient() {
                @Override
                public SearchEngineType getEngineType() {
                    return SearchEngineType.OPENSEARCH;
                }

                @Override
                public <T> T getNativeClient(Class<T> clientClass) {
                    return null;
                }

                @Override
                public ElasticsearchClient getElasticsearchClient() {
                    throw new UnsupportedOperationException("Custom Mock");
                }

                @Override
                public void close() throws IOException {
                }
            };
        }
    }
}
