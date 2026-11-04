package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.autoconfigure.AtengMilvusAutoConfiguration;
import io.github.atengk.boot.milvus.health.MilvusHealthIndicator;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milvus 自动装配行为单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AtengMilvusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMilvusAutoConfiguration.class));

    @Test
    @DisplayName("测试当存在客户端 Bean 时自动装配核心门面与健康探针")
    void testAutoConfigurationWithClient() {
        contextRunner.withUserConfiguration(CustomMilvusConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(MilvusTemplate.class);
                    assertThat(context).hasSingleBean(MilvusHealthIndicator.class);

                    MilvusTemplate template = context.getBean(MilvusTemplate.class);
                    assertThat(template.getClient()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试关闭健康探针配置时仅装配核心门面不装配探针")
    void testHealthIndicatorDisabled() {
        contextRunner.withUserConfiguration(CustomMilvusConfig.class)
                .withPropertyValues("management.health.milvus.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusTemplate.class);
                    assertThat(context).doesNotHaveBean(MilvusHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("测试当 enabled=false 时自动装配完全不生效")
    void testDisabledConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.milvus.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                    assertThat(context).doesNotHaveBean(MilvusTemplate.class);
                    assertThat(context).doesNotHaveBean(MilvusHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("测试业务端自定义 Bean 时优先接管 (@ConditionalOnMissingBean)")
    void testCustomBeanOverride() {
        contextRunner.withUserConfiguration(CustomMilvusConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(MilvusTemplate.class);

                    MilvusTemplate template = context.getBean(MilvusTemplate.class);
                    assertThat(template.getClient()).isNotNull();
                });
    }

    @Configuration
    static class CustomMilvusConfig {

        @Bean
        public MilvusClientV2 customClient() {
            return Mockito.mock(MilvusClientV2.class);
        }
    }
}
