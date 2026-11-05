package io.github.atengk.boot.ai.autoconfigure;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.rag.parser.DocumentParserManager;
import io.github.atengk.boot.ai.rag.parser.ExcelDocumentParser;
import io.github.atengk.boot.ai.rag.parser.HtmlDocumentParser;
import io.github.atengk.boot.ai.rag.parser.PdfDocumentParser;
import io.github.atengk.boot.ai.rag.parser.TextDocumentParser;
import io.github.atengk.boot.ai.rag.parser.WordDocumentParser;
import io.github.atengk.boot.ai.rag.parser.multimodal.VisionCaptionEnhancer;
import io.github.atengk.boot.ai.rag.parser.multimodal.VisionModelResolver;
import io.github.atengk.boot.ai.rag.splitter.DocumentSplitter;
import io.github.atengk.boot.ai.rag.splitter.ParentChildSplitter;
import io.github.atengk.boot.ai.rag.splitter.TableSplitter;
import io.github.atengk.boot.ai.rag.splitter.TokenCharacterSplitter;
import io.github.atengk.boot.ai.registry.EmbeddingModelRegistry;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI RAG 企业知识库与向量检索自适应自动装配单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
@DisplayName("AI RAG 企业知识库自适应自动装配单元测试")
class AtengAiRagAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAiRagAutoConfiguration.class));

    @Configuration
    static class CustomEmbeddingStoreConfiguration {
        @Bean
        public EmbeddingStore<TextSegment> customEmbeddingStore() {
            return Mockito.mock(EmbeddingStore.class);
        }
    }

    @Configuration
    static class CustomEmbeddingModelConfiguration {
        @Bean
        public EmbeddingModel customEmbeddingModel() {
            return Mockito.mock(EmbeddingModel.class);
        }
    }

    @Configuration
    static class CustomChatModelConfiguration {
        @Bean
        public dev.langchain4j.model.chat.ChatLanguageModel customChatModel() {
            return Mockito.mock(dev.langchain4j.model.chat.ChatLanguageModel.class);
        }
    }

    @Test
    @DisplayName("无外部向量存储 Bean 时自动平滑回退单机 InMemoryEmbeddingStore")
    void testDefaultInMemoryStoreFallbackWithoutExternalStore() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingStore.class);
            assertThat(context.getBean(EmbeddingStore.class)).isInstanceOf(InMemoryEmbeddingStore.class);
            // 未配置 API Key 时，EmbeddingModel 不装配，EmbeddingTemplate 与 ContentRetriever 处于平滑待命状态
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(EmbeddingTemplate.class);
            assertThat(context.getBean(EmbeddingTemplate.class).isAvailable()).isFalse();
            assertThat(context).hasSingleBean(ContentRetriever.class);

            // 验证 RAG 文档解析 SPI 组件与 Office 解析器自动装配
            assertThat(context).hasSingleBean(TextDocumentParser.class);
            assertThat(context).hasSingleBean(HtmlDocumentParser.class);
            assertThat(context).hasSingleBean(PdfDocumentParser.class);
            assertThat(context).hasSingleBean(WordDocumentParser.class);
            assertThat(context).hasSingleBean(ExcelDocumentParser.class);
            assertThat(context).hasSingleBean(DocumentParserManager.class);
            assertThat(context.getBean(DocumentParserManager.class).getParsers()).hasSize(5);

            // 验证高级切片套件自动装配
            assertThat(context).hasSingleBean(TableSplitter.class);
            assertThat(context).hasSingleBean(TokenCharacterSplitter.class);
            assertThat(context).hasSingleBean(ParentChildSplitter.class);
            assertThat(context).hasBean("documentSplitter");
            assertThat(context.getBean(DocumentSplitter.class)).isNotNull();

            // 验证多模态组件自动装配
            assertThat(context).hasSingleBean(VisionModelResolver.class);
            assertThat(context).hasSingleBean(VisionCaptionEnhancer.class);

            // 验证 RAG ETL 摄取管道自动装配
            assertThat(context).hasSingleBean(RagIngestionTemplate.class);
        });
    }

    @Test
    @DisplayName("根据 ateng.boot.ai.rag.splitter.type 配置平滑切换默认 DocumentSplitter 门面")
    void testSplitterTypeSwitching() {
        contextRunner.withPropertyValues("ateng.boot.ai.rag.splitter.type=TOKEN")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DocumentSplitter.class)).isInstanceOf(TokenCharacterSplitter.class);
                });

        contextRunner.withPropertyValues("ateng.boot.ai.rag.splitter.type=PARENT_CHILD")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DocumentSplitter.class)).isInstanceOf(ParentChildSplitter.class);
                });

        contextRunner.withPropertyValues("ateng.boot.ai.rag.splitter.type=TABLE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DocumentSplitter.class)).isInstanceOf(TableSplitter.class);
                });
    }

    @Test
    @DisplayName("存在自定义外部向量存储 Bean (如 MilvusEmbeddingStore) 时优雅退让，不注册 InMemoryEmbeddingStore")
    void testCustomEmbeddingStoreTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomEmbeddingStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddingStore.class);
                    assertThat(context.getBean(EmbeddingStore.class)).isNotInstanceOf(InMemoryEmbeddingStore.class);
                });
    }

    @Test
    @DisplayName("配置全局主模型 API Key 时自动全链路装配 EmbeddingModel, EmbeddingTemplate 与 ContentRetriever")
    void testRagAutoConfigurationWithGlobalApiKey() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.model.api-key=test-key-global",
                "ateng.boot.ai.rag.max-results=5",
                "ateng.boot.ai.rag.min-score=0.75"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(EmbeddingTemplate.class);
            assertThat(context).hasSingleBean(EmbeddingStore.class);
            assertThat(context).hasSingleBean(ContentRetriever.class);
            assertThat(context.getBean(ContentRetriever.class)).isInstanceOf(EmbeddingStoreContentRetriever.class);
        });
    }

    @Test
    @DisplayName("配置 RAG 专属模型 API Key 时自动装配并覆盖配置")
    void testRagAutoConfigurationWithDedicatedApiKey() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.rag.model.api-key=test-key-rag",
                "ateng.boot.ai.rag.model.base-url=https://custom-embedding.domain.com/v1",
                "ateng.boot.ai.rag.model-name=text-embedding-3-large"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(EmbeddingTemplate.class);
            assertThat(context).hasSingleBean(ContentRetriever.class);
        });
    }

    @Test
    @DisplayName("当已存在自定义 EmbeddingModel 时遵循 @ConditionalOnMissingBean 优先使用自定义 Bean")
    void testCustomEmbeddingModelTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomEmbeddingModelConfiguration.class)
                .withPropertyValues("ateng.boot.ai.model.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context.getBean(EmbeddingModel.class)).isNotInstanceOf(dev.langchain4j.model.openai.OpenAiEmbeddingModel.class);
                    assertThat(context).hasSingleBean(EmbeddingTemplate.class);
                    assertThat(context).hasSingleBean(ContentRetriever.class);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.ai.rag.enabled=false 时自动关闭 RAG 相关所有组件装配")
    void testRagDisabledProperty() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.rag.enabled=false",
                "ateng.boot.ai.model.api-key=test-key"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EmbeddingStore.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
            assertThat(context).doesNotHaveBean(EmbeddingTemplate.class);
            assertThat(context).doesNotHaveBean(ContentRetriever.class);
        });
    }

    @Test
    @DisplayName("配置 rerank.enabled=true 且具备有效 base-url 时自动装配 Reranker")
    void testRerankAutoConfigurationWithBaseUrl() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.model.api-key=test-key",
                "ateng.boot.ai.rag.rerank.enabled=true",
                "ateng.boot.ai.rag.rerank.base-url=https://api.siliconflow.cn/v1"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(io.github.atengk.boot.ai.rag.rerank.Reranker.class);
        });
    }

    @Test
    @DisplayName("配置 rerank.enabled=true 但遗漏 base-url 时平滑探测跳过装配，绝不导致启动崩溃")
    void testRerankAutoConfigurationWithoutBaseUrlDoesNotCrash() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.model.api-key=test-key",
                "ateng.boot.ai.rag.rerank.enabled=true"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.ai.rag.rerank.Reranker.class);
        });
    }

    @Test
    @DisplayName("配置 query-transform.enabled=true 且容器存在 ChatLanguageModel 时自动装配 LlmQueryTransformer")
    void testQueryTransformerAutoConfiguration() {
        contextRunner.withUserConfiguration(CustomChatModelConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.ai.model.api-key=test-key",
                        "ateng.boot.ai.rag.query-transform.enabled=true",
                        "ateng.boot.ai.rag.query-transform.max-history-messages=8"
                ).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(io.github.atengk.boot.ai.rag.query.QueryTransformer.class);
                });
    }

    @Test
    @DisplayName("默认自动装配 DocumentSplitter 与 TableSplitter 实例")
    void testDocumentSplitterAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("documentSplitter");
            assertThat(context).hasSingleBean(TableSplitter.class);
        });
    }

    @Test
    @DisplayName("自动装配 EmbeddingModelRegistry 并在配置命名 embeddings 时自动注册多模型")
    void testMultiModelEmbeddingRegistryAutoConfiguration() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.model.api-key=default-key",
                "ateng.boot.ai.rag.embeddings.bge-large.base-url=https://api.siliconflow.cn/v1",
                "ateng.boot.ai.rag.embeddings.bge-large.api-key=silicon-key",
                "ateng.boot.ai.rag.embeddings.bge-large.model-name=BAAI/bge-large-zh-v1.5",
                "ateng.boot.ai.rag.embeddings.bge-large.dimensions=1024",
                "ateng.boot.ai.rag.embeddings.text-3-large.model-name=text-embedding-3-large",
                "ateng.boot.ai.rag.embeddings.text-3-large.dimensions=3072"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingModelRegistry.class);
            EmbeddingModelRegistry registry = context.getBean(EmbeddingModelRegistry.class);

            assertThat(registry.hasModel("default")).isTrue();
            assertThat(registry.hasModel("bge-large")).isTrue();
            assertThat(registry.hasModel("BAAI/bge-large-zh-v1.5")).isTrue();
            assertThat(registry.hasModel("text-3-large")).isTrue();

            EmbeddingTemplate template = context.getBean(EmbeddingTemplate.class);
            assertThat(template.isAvailable()).isTrue();
            assertThat(template.getRegistry()).isSameAs(registry);
        });
    }
}
