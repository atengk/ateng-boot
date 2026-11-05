package io.github.atengk.boot.ai.autoconfigure;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
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
import io.github.atengk.boot.ai.rag.query.LlmQueryTransformer;
import io.github.atengk.boot.ai.rag.query.QueryTransformer;
import io.github.atengk.boot.ai.rag.query.RewriteQueryRetriever;
import io.github.atengk.boot.ai.rag.rerank.OpenAiCompatibleReranker;
import io.github.atengk.boot.ai.rag.rerank.Reranker;
import io.github.atengk.boot.ai.rag.rerank.RerankingContentRetriever;
import io.github.atengk.boot.ai.rag.splitter.DocumentSplitter;
import io.github.atengk.boot.ai.rag.splitter.RecursiveCharacterSplitter;
import io.github.atengk.boot.ai.registry.EmbeddingModelRegistry;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.util.StringUtils;

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
     * 当且仅当 rerank.enabled=true 且显式配置了 base-url 时生效的装配守卫条件
     */
    static class RerankCondition extends AllNestedConditions {

        public RerankCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "ateng.boot.ai.rag.rerank", name = "enabled", havingValue = "true")
        static class EnabledProperty {
        }

        @ConditionalOnProperty(prefix = "ateng.boot.ai.rag.rerank", name = "base-url")
        static class BaseUrlProperty {
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
        return OpenAiModelFactory.createEmbeddingModel(config, config.getDimensions());
    }

    /**
     * 自动装配多向量化模型统一注册中心 (EmbeddingModelRegistry)
     *
     * @param defaultEmbeddingModelProvider 默认向量化模型提供者 (可选)
     * @param properties                    AI 根配置属性
     * @return EmbeddingModelRegistry 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModelRegistry.class)
    public EmbeddingModelRegistry embeddingModelRegistry(
            ObjectProvider<EmbeddingModel> defaultEmbeddingModelProvider,
            AtengAiProperties properties) {
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry();
        AtengAiRagProperties rag = properties.getRag();

        // 1. 注册默认主模型 (若存在)
        EmbeddingModel defaultModel = defaultEmbeddingModelProvider.getIfAvailable();
        if (defaultModel != null) {
            String defaultAlias = rag != null && StringUtils.hasText(rag.getModelName()) ? rag.getModelName() : null;
            registry.registerDefault(defaultModel, defaultAlias);
        }

        // 2. 遍历装配命名 embeddings 字典
        if (rag != null && rag.getEmbeddings() != null && !rag.getEmbeddings().isEmpty()) {
            for (Map.Entry<String, AiModelConfig> entry : rag.getEmbeddings().entrySet()) {
                String name = entry.getKey();
                AiModelConfig rawConfig = entry.getValue();
                if (rawConfig == null) {
                    continue;
                }
                AiModelConfig effectiveConfig = rag.resolveNamedEmbeddingConfig(name, properties.getModel());
                if (effectiveConfig.isConfigured() || StringUtils.hasText(effectiveConfig.getApiKey())) {
                    Integer dim = effectiveConfig.getDimensions();
                    EmbeddingModel namedModel = OpenAiModelFactory.createEmbeddingModel(effectiveConfig, dim);
                    registry.register(name, namedModel);
                    if (StringUtils.hasText(effectiveConfig.getModelName()) && !name.equalsIgnoreCase(effectiveConfig.getModelName())) {
                        registry.register(effectiveConfig.getModelName(), namedModel);
                    }
                    log.info("自动装配命名 EmbeddingModel [name={}, modelName={}, baseUrl={}, dimension={}]",
                            name, effectiveConfig.getModelName(), effectiveConfig.getBaseUrl(), dim);
                } else {
                    log.warn("命名 EmbeddingModel [{}] 未配置有效 api-key，跳过自动注册", name);
                }
            }
        }
        return registry;
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
     * 自动装配统一向量化嵌入门面 EmbeddingTemplate (支持待命平滑探测、多模型动态路由与默认向量库自动绑定)
     *
     * @param embeddingModelProvider         向量化模型提供者
     * @param embeddingModelRegistryProvider 多向量化模型注册中心提供者
     * @param embeddingStoreProvider         默认向量存储库提供者
     * @param properties                     AI 根配置属性
     * @return EmbeddingTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingTemplate.class)
    public EmbeddingTemplate embeddingTemplate(
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            ObjectProvider<EmbeddingModelRegistry> embeddingModelRegistryProvider,
            ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
            AtengAiProperties properties) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        EmbeddingModelRegistry registry = embeddingModelRegistryProvider.getIfAvailable();
        if (embeddingModel == null && (registry == null || registry.size() == 0)) {
            log.info("ateng-boot-ai 未检测到任何有效的大模型 API Key 配置，EmbeddingTemplate 处于待命状态。");
        }
        return new EmbeddingTemplate(embeddingModel, registry, embeddingStoreProvider, properties);
    }

    /**
     * 自动组装并注册 RAG 知识库检索组件 ContentRetriever (支持向量召回、两阶段重排与多轮查询改写自适应包装)
     *
     * @param embeddingStoreProvider   向量存储实现提供者
     * @param embeddingModelProvider   向量化模型提供者
     * @param rerankerProvider         重排模型提供者 (可选)
     * @param queryTransformerProvider 多轮查询改写器提供者 (可选)
     * @param properties               AI 根配置属性
     * @return ContentRetriever 实例
     */
    @Bean
    @ConditionalOnMissingBean(ContentRetriever.class)
    public ContentRetriever contentRetriever(
            ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            ObjectProvider<Reranker> rerankerProvider,
            ObjectProvider<QueryTransformer> queryTransformerProvider,
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
        Reranker reranker = rerankerProvider.getIfAvailable();
        int initialResults = reranker != null ? Math.max(rag.getMaxResults() * 3, 10) : rag.getMaxResults();

        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder builder = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(initialResults);
        if (rag.getMinScore() != null && reranker == null) {
            builder.minScore(rag.getMinScore());
        }
        ContentRetriever baseRetriever = builder.build();

        ContentRetriever effectiveRetriever = baseRetriever;
        if (reranker != null) {
            int topK = rag.getRerank() != null && rag.getRerank().getTopK() > 0 ? rag.getRerank().getTopK() : rag.getMaxResults();
            Double rerankMinScore = rag.getRerank() != null ? rag.getRerank().getMinScore() : rag.getMinScore();
            log.info("自动装配并挂载 RerankingContentRetriever 两阶段重排检索器 [topK={}, minScore={}]", topK, rerankMinScore);
            effectiveRetriever = new RerankingContentRetriever(baseRetriever, reranker, topK, rerankMinScore);
        }

        QueryTransformer queryTransformer = queryTransformerProvider.getIfAvailable();
        if (queryTransformer != null) {
            log.info("自动装配并挂载 RewriteQueryRetriever 多轮意图消解检索器");
            effectiveRetriever = new RewriteQueryRetriever(effectiveRetriever, queryTransformer);
        }

        return effectiveRetriever;
    }

    /**
     * 自动装配默认 OpenAiCompatibleReranker (当配置 ateng.boot.ai.rag.rerank.enabled=true 时生效)
     *
     * @param properties AI 根配置属性
     * @return Reranker 实例
     */
    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    @Conditional(RerankCondition.class)
    public Reranker openAiCompatibleReranker(AtengAiProperties properties) {
        AtengAiRagProperties.RerankProperties rerankProps = properties.getRag().getRerank();
        String apiKey = StringUtils.hasText(rerankProps.getApiKey())
                ? rerankProps.getApiKey()
                : properties.getModel().getApiKey();
        log.info("自动装配默认 OpenAiCompatibleReranker [baseUrl={}, modelName={}]",
                rerankProps.getBaseUrl(), rerankProps.getModelName());
        return new OpenAiCompatibleReranker(
                rerankProps.getBaseUrl(),
                apiKey,
                rerankProps.getModelName(),
                rerankProps.getTimeout(),
                rerankProps.getMinScore()
        );
    }

    /**
     * 自动装配默认基于大模型的多轮意图消解查询改写器 (当配置 ateng.boot.ai.rag.query-transform.enabled=true 时生效)
     *
     * @param properties AI 根配置属性
     * @param chatModelProvider 聊天模型提供者
     * @return QueryTransformer 实例
     */
    @Bean
    @ConditionalOnMissingBean(QueryTransformer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.rag.query-transform", name = "enabled", havingValue = "true")
    public QueryTransformer llmQueryTransformer(AtengAiProperties properties,
                                                ObjectProvider<ChatLanguageModel> chatModelProvider) {
        ChatLanguageModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("已配置 ateng.boot.ai.rag.query-transform.enabled=true，但未检测到可用的 ChatLanguageModel Bean，跳过 LlmQueryTransformer 自动装配");
            return null;
        }
        AtengAiRagProperties.QueryTransformProperties qtProps = properties.getRag().getQueryTransform();
        int maxHistory = qtProps != null && qtProps.getMaxHistoryMessages() > 0
                ? qtProps.getMaxHistoryMessages()
                : 6;
        log.info("自动装配默认 LlmQueryTransformer 多轮意图消解查询改写器 [maxHistoryMessages={}]", maxHistory);
        return new LlmQueryTransformer(chatModel, maxHistory);
    }

    /**
     * 自动装配默认原生长文本智能切片门面 DocumentSplitter (RecursiveCharacterSplitter)
     *
     * @param properties AI 根配置属性
     * @return DocumentSplitter 实例
     */
    @Bean
    @ConditionalOnMissingBean(DocumentSplitter.class)
    public DocumentSplitter documentSplitter(AtengAiProperties properties) {
        AtengAiRagProperties rag = properties.getRag();
        int chunkSize = rag != null && rag.getChunkSize() > 0 ? rag.getChunkSize() : RecursiveCharacterSplitter.DEFAULT_CHUNK_SIZE;
        int chunkOverlap = rag != null && rag.getChunkOverlap() >= 0 && rag.getChunkOverlap() < chunkSize
                ? rag.getChunkOverlap()
                : RecursiveCharacterSplitter.DEFAULT_CHUNK_OVERLAP;
        log.info("自动装配默认原生长文本智能切片器 (RecursiveCharacterSplitter) [chunkSize={}, chunkOverlap={}]",
                chunkSize, chunkOverlap);
        return new RecursiveCharacterSplitter(chunkSize, chunkOverlap);
    }
}
