package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.autoconfigure.langchain4j.MilvusLangChain4jAutoConfiguration;
import io.github.atengk.boot.milvus.langchain4j.MilvusEmbeddingStore;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusLangChain4jAutoConfiguration 条件装配单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusLangChain4jAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MilvusLangChain4jAutoConfiguration.class))
            .withUserConfiguration(MockTemplateConfig.class);

    @Test
    @DisplayName("测试在存在 MilvusTemplate 时默认装配 MilvusEmbeddingStore")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MilvusEmbeddingStore.class);
            MilvusEmbeddingStore store = context.getBean(MilvusEmbeddingStore.class);
            assertThat(store.getCollectionName()).isEqualTo("langchain4j_embeddings");
            assertThat(store.getDimension()).isEqualTo(1536);
        });
    }

    @Test
    @DisplayName("测试当 enabled=false 时 LangChain4j 自动装配不生效")
    void testDisabledAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.milvus.langchain4j.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MilvusEmbeddingStore.class));
    }

    @Test
    @DisplayName("测试业务端自定义 Bean 时平滑接管 (@ConditionalOnMissingBean)")
    void testCustomEmbeddingStoreOverride() {
        contextRunner.withUserConfiguration(CustomStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusEmbeddingStore.class);
                    MilvusEmbeddingStore store = context.getBean(MilvusEmbeddingStore.class);
                    assertThat(store.getCollectionName()).isEqualTo("custom_rag_kb");
                });
    }

    @Configuration
    static class MockTemplateConfig {
        @Bean
        public MilvusTemplate milvusTemplate() {
            return Mockito.mock(MilvusTemplate.class);
        }
    }

    @Configuration
    static class CustomStoreConfig {
        @Bean
        public MilvusEmbeddingStore customStore(MilvusTemplate template) {
            return MilvusEmbeddingStore.builder()
                    .template(template)
                    .collectionName("custom_rag_kb")
                    .build();
        }
    }
}
