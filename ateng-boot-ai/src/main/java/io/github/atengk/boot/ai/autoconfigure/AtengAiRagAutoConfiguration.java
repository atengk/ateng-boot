package io.github.atengk.boot.ai.autoconfigure;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * AI RAG 企业知识库与向量检索自适应自动装配
 * <p>
 * 1. 基于 LangChain4j 标准 SPI 自动装配向量化模型 {@link EmbeddingModel} 与命令式门面 {@link EmbeddingTemplate}；
 * 2. 自动探测 Spring 容器中由其他子模块 (如 {@code ateng-boot-milvus}) 注册的 {@link EmbeddingStore}，缺省时自动降级单机内存向量库 {@link InMemoryEmbeddingStore}；
 * 3. 自动组装并注册 {@link ContentRetriever} 检索器受管 Bean，打通声明式 {@link io.github.atengk.boot.ai.annotation.AiAgent#contentRetriever()} 的自动挂载。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration"
})
@ConditionalOnClass({EmbeddingModel.class, EmbeddingStore.class, ContentRetriever.class})
@ConditionalOnProperty(prefix = "ateng.boot.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAiProperties.class)
public class AtengAiRagAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengAiRagAutoConfiguration.class);

    /**
     * 当全局主模型或 RAG 专属模型配置中任一配置了 API Key 时生效的条件
     */
    static class EmbeddingModelCondition extends AnyNestedCondition {

        public EmbeddingModelCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "ateng.boot.ai.rag.model", name = "api-key")
        static class RagApiKeyProperty {
        }

        @ConditionalOnProperty(prefix = "ateng.boot.ai.model", name = "api-key")
        static class ModelApiKeyProperty {
        }
    }

    /**
     * 自动装配默认 OpenAiEmbeddingModel 向量化模型
     *
     * @param properties AI 根配置属性
     * @return EmbeddingModel 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @Conditional(EmbeddingModelCondition.class)
    public EmbeddingModel openAiEmbeddingModel(AtengAiProperties properties) {
        AtengAiRagProperties rag = properties.getRag();
        AiModelConfig config = rag.resolveEmbeddingConfig(properties.getModel());
        log.info("自动装配默认 OpenAiEmbeddingModel [modelName={}, baseUrl={}]",
                config.getModelName(), config.getBaseUrl());
        return OpenAiModelFactory.createEmbeddingModel(config, rag.getDimensions());
    }

    /**
     * 自动装配单机内存向量存储库 (当容器中未检测到由外部持久化中间件如 Milvus 注册的 EmbeddingStore 时平滑兜底降级)
     *
     * @return InMemoryEmbeddingStore 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        log.info("未检测到自定义向量存储 Bean (如 MilvusEmbeddingStore)，AI RAG 知识库启用单机内存向量存储 (InMemoryEmbeddingStore)");
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 自动装配统一向量化嵌入门面 EmbeddingTemplate (支持待命平滑探测)
     *
     * @param embeddingModelProvider 向量化模型提供者
     * @param properties             AI 根配置属性
     * @return EmbeddingTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingTemplate.class)
    public EmbeddingTemplate embeddingTemplate(
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            AtengAiProperties properties) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.info("ateng-boot-ai 未检测到任何有效的大模型 API Key 配置，EmbeddingTemplate 处于待命状态。");
        }
        return new EmbeddingTemplate(embeddingModel, properties);
    }

    /**
     * 自动组装并注册 RAG 向量检索组件 ContentRetriever (EmbeddingStoreContentRetriever)
     *
     * @param embeddingStoreProvider 向量存储实现提供者
     * @param embeddingModelProvider 向量化模型提供者
     * @param properties             AI 根配置属性
     * @return ContentRetriever 实例
     */
    @Bean
    @ConditionalOnMissingBean(ContentRetriever.class)
    public ContentRetriever contentRetriever(
            ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            AtengAiProperties properties) {
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreProvider.getIfAvailable();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingStore == null || embeddingModel == null) {
            log.info("ateng-boot-ai 未检测到完整的 EmbeddingStore 或 EmbeddingModel 配置，ContentRetriever 处于待命状态。");
            return query -> {
                throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                        "未配置有效的 EmbeddingModel 或 EmbeddingStore，无法执行 RAG 知识库检索");
            };
        }
        AtengAiRagProperties rag = properties.getRag();
        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder builder = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(rag.getMaxResults());
        if (rag.getMinScore() != null) {
            builder.minScore(rag.getMinScore());
        }
        log.info("自动装配 RAG 知识库向量检索器 (EmbeddingStoreContentRetriever) [maxResults={}, minScore={}]",
                rag.getMaxResults(), rag.getMinScore());
        return builder.build();
    }
}
