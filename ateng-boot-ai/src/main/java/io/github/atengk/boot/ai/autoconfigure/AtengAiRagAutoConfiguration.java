package io.github.atengk.boot.ai.autoconfigure;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFactExtractor;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.properties.VectorStoreProperties;
import io.github.atengk.boot.ai.rag.filter.DynamicFilterContentRetriever;
import io.github.atengk.boot.ai.rag.fusion.Bm25KeywordRetriever;
import io.github.atengk.boot.ai.rag.fusion.HybridContentRetriever;
import io.github.atengk.boot.ai.rag.parser.DocumentParser;
import io.github.atengk.boot.ai.rag.parser.DocumentParserManager;
import io.github.atengk.boot.ai.rag.parser.ExcelDocumentParser;
import io.github.atengk.boot.ai.rag.parser.HtmlDocumentParser;
import io.github.atengk.boot.ai.rag.parser.PdfDocumentParser;
import io.github.atengk.boot.ai.rag.parser.TextDocumentParser;
import io.github.atengk.boot.ai.rag.parser.WordDocumentParser;
import io.github.atengk.boot.ai.rag.parser.multimodal.VisionCaptionEnhancer;
import io.github.atengk.boot.ai.rag.parser.multimodal.VisionModelResolver;
import io.github.atengk.boot.ai.rag.query.LlmQueryTransformer;
import io.github.atengk.boot.ai.rag.query.QueryTransformer;
import io.github.atengk.boot.ai.rag.query.RewriteQueryRetriever;
import io.github.atengk.boot.ai.rag.rerank.OpenAiCompatibleReranker;
import io.github.atengk.boot.ai.rag.rerank.Reranker;
import io.github.atengk.boot.ai.rag.rerank.RerankingContentRetriever;
import io.github.atengk.boot.ai.rag.splitter.DocumentSplitter;
import io.github.atengk.boot.ai.rag.splitter.ParentChildSplitter;
import io.github.atengk.boot.ai.rag.splitter.RecursiveCharacterSplitter;
import io.github.atengk.boot.ai.rag.splitter.TableSplitter;
import io.github.atengk.boot.ai.rag.splitter.TokenCharacterSplitter;
import io.github.atengk.boot.ai.rag.store.VectorStoreFactory;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.registry.EmbeddingModelRegistry;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
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
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
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
        "io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration",
        "io.github.atengk.boot.storage.autoconfigure.AtengStorageAutoConfiguration",
        "io.github.atengk.boot.milvus.autoconfigure.langchain4j.MilvusLangChain4jAutoConfiguration"
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
     * 当且仅当 store.type 未显式配置为 CUSTOM 时生效的默认向量库自动装配守卫条件
     */
    static class VectorStoreCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String type = context.getEnvironment().getProperty("ateng.boot.ai.rag.store.type");
            return type == null || !type.equalsIgnoreCase("CUSTOM");
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
     * 自动装配 RAG 知识库向量存储库 (支持 AUTO 自动探测并平滑回退、IN_MEMORY、REDIS、PGVECTOR、ELASTICSEARCH 等)
     *
     * @param properties AI 根配置属性
     * @return EmbeddingStore 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    @Conditional(VectorStoreCondition.class)
    public EmbeddingStore<TextSegment> embeddingStore(AtengAiProperties properties) {
        AtengAiRagProperties rag = properties.getRag();
        VectorStoreProperties storeProps = rag != null && rag.getStore() != null
                ? rag.getStore()
                : new VectorStoreProperties();
        return VectorStoreFactory.createEmbeddingStore(storeProps);
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
     * 自动装配默认纯内存轻量级 BM25 关键词稀疏检索器 (Bm25KeywordRetriever)
     *
     * @return Bm25KeywordRetriever 实例
     */
    @Bean
    @ConditionalOnMissingBean(Bm25KeywordRetriever.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.rag.hybrid", name = "enabled", havingValue = "true")
    public Bm25KeywordRetriever bm25KeywordRetriever(AtengAiProperties properties) {
        int maxResults = properties.getRag() != null && properties.getRag().getMaxResults() > 0
                ? properties.getRag().getMaxResults()
                : 10;
        log.info("自动装配默认纯内存 BM25 关键词稀疏检索器 (Bm25KeywordRetriever) [maxResults={}]", maxResults);
        return new Bm25KeywordRetriever(Collections::emptyList, maxResults);
    }

    /**
     * 自动组装并注册 RAG 知识库检索组件 ContentRetriever (支持向量召回、混合多路融合、两阶段重排与多轮查询改写自适应包装)
     *
     * @param embeddingStoreProvider   向量存储实现提供者
     * @param embeddingModelProvider   向量化模型提供者
     * @param bm25RetrieverProvider    BM25 关键词稀疏检索器提供者 (可选)
     * @param rerankerProvider         重排模型提供者 (可选)
     * @param queryTransformerProvider 多轮查询改写器提供者 (可选)
     * @param properties               AI 根配置属性
     * @return ContentRetriever 实例
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "contentRetriever")
    public ContentRetriever contentRetriever(
            ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            ObjectProvider<Bm25KeywordRetriever> bm25RetrieverProvider,
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

        Double minScore = (rag.getMinScore() != null && reranker == null) ? rag.getMinScore() : null;
        ContentRetriever baseRetriever = new DynamicFilterContentRetriever(
                embeddingStore,
                embeddingModel,
                initialResults,
                minScore
        );

        ContentRetriever effectiveRetriever = baseRetriever;

        // 若启用稠密与稀疏混合多路召回 (Hybrid Search)
        AtengAiRagProperties.HybridProperties hybridProps = rag != null ? rag.getHybrid() : null;
        if (hybridProps != null && hybridProps.isEnabled()) {
            Bm25KeywordRetriever bm25Retriever = bm25RetrieverProvider.getIfAvailable();
            if (bm25Retriever != null) {
                log.info("自动装配并挂载 HybridContentRetriever 稠密与稀疏多路混合检索器 [denseWeight={}, sparseWeight={}, rrfK={}]",
                        hybridProps.getDenseWeight(), hybridProps.getSparseWeight(), hybridProps.getRrfK());
                effectiveRetriever = new HybridContentRetriever(
                        baseRetriever,
                        bm25Retriever,
                        initialResults,
                        hybridProps.getRrfK(),
                        hybridProps.getDenseWeight(),
                        hybridProps.getSparseWeight()
                );
            } else {
                log.warn("已配置 ateng.boot.ai.rag.hybrid.enabled=true，但未检测到稀疏关键词检索器提供者，平滑回退仅使用稠密向量检索");
            }
        }

        if (reranker != null) {
            int topK = rag.getRerank() != null && rag.getRerank().getTopK() > 0 ? rag.getRerank().getTopK() : rag.getMaxResults();
            Double rerankMinScore = rag.getRerank() != null ? rag.getRerank().getMinScore() : rag.getMinScore();
            log.info("自动装配并挂载 RerankingContentRetriever 两阶段重排检索器 [topK={}, minScore={}]", topK, rerankMinScore);
            effectiveRetriever = new RerankingContentRetriever(effectiveRetriever, reranker, topK, rerankMinScore);
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
     * 自动装配基于 Token 预算防溢出切片器 (TokenCharacterSplitter)
     *
     * @param properties AI 根配置属性
     * @param tokenizerProvider 可选分词器提供者 (缺省时平滑使用 OpenAiTokenizer 或启发式估算器保底)
     * @return TokenCharacterSplitter 实例
     */
    @Bean
    @ConditionalOnMissingBean(TokenCharacterSplitter.class)
    public TokenCharacterSplitter tokenCharacterSplitter(AtengAiProperties properties,
                                                         ObjectProvider<Tokenizer> tokenizerProvider) {
        AtengAiRagProperties rag = properties.getRag();
        AtengAiRagProperties.TokenSplitterProperties tokenProps = rag != null && rag.getSplitter() != null
                ? rag.getSplitter().getToken()
                : new AtengAiRagProperties.TokenSplitterProperties();
        Tokenizer tokenizer = tokenizerProvider.getIfAvailable();
        log.info("自动装配 Token 预算防溢出切片器 (TokenCharacterSplitter) [maxTokens={}, tokenOverlap={}, safetyMargin={}]",
                tokenProps.getMaxTokens(), tokenProps.getTokenOverlap(), tokenProps.getSafetyMargin());
        return new TokenCharacterSplitter(
                tokenProps.getMaxTokens(),
                tokenProps.getTokenOverlap(),
                tokenizer,
                tokenProps.getSafetyMargin()
        );
    }

    /**
     * 自动装配原生父子分级智能切片器 (ParentChildSplitter)
     *
     * @param properties AI 根配置属性
     * @return ParentChildSplitter 实例
     */
    @Bean
    @ConditionalOnMissingBean(ParentChildSplitter.class)
    public ParentChildSplitter parentChildSplitter(AtengAiProperties properties) {
        AtengAiRagProperties rag = properties.getRag();
        AtengAiRagProperties.ParentChildSplitterProperties pcProps = rag != null && rag.getSplitter() != null
                ? rag.getSplitter().getParentChild()
                : new AtengAiRagProperties.ParentChildSplitterProperties();
        log.info("自动装配父子分级切片器 (ParentChildSplitter) [parentSize={}, childSize={}, selfContained={}]",
                pcProps.getParentChunkSize(), pcProps.getChildChunkSize(), pcProps.isSelfContainedParent());
        return new ParentChildSplitter(
                pcProps.getParentChunkSize(),
                pcProps.getParentChunkOverlap(),
                pcProps.getChildChunkSize(),
                pcProps.getChildChunkOverlap(),
                pcProps.isSelfContainedParent()
        );
    }

    /**
     * 自动装配默认原生长文本智能切片门面 DocumentSplitter
     * <p>
     * 依据 {@code ateng.boot.ai.rag.splitter.type} 配置类型自动切源绑定对应切片器门面，
     * 默认采用高性能层级递归字符切片器 {@link RecursiveCharacterSplitter}。
     * </p>
     *
     * @param properties AI 根配置属性
     * @param tokenSplitterProvider Token 预算切片器提供者
     * @param parentChildSplitterProvider 父子分级切片器提供者
     * @param tableSplitterProvider 表格感知分块切片器提供者
     * @return DocumentSplitter 实例
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "documentSplitter")
    public DocumentSplitter documentSplitter(AtengAiProperties properties,
                                             ObjectProvider<TokenCharacterSplitter> tokenSplitterProvider,
                                             ObjectProvider<ParentChildSplitter> parentChildSplitterProvider,
                                             ObjectProvider<TableSplitter> tableSplitterProvider) {
        AtengAiRagProperties rag = properties.getRag();
        AtengAiRagProperties.SplitterType type = rag != null && rag.getSplitter() != null && rag.getSplitter().getType() != null
                ? rag.getSplitter().getType()
                : AtengAiRagProperties.SplitterType.RECURSIVE;

        if (type == AtengAiRagProperties.SplitterType.TOKEN) {
            TokenCharacterSplitter splitter = tokenSplitterProvider.getIfAvailable();
            if (splitter != null) {
                log.info("根据配置选用 Token 预算防溢出切片器作为默认 DocumentSplitter 门面");
                return splitter;
            }
        } else if (type == AtengAiRagProperties.SplitterType.PARENT_CHILD) {
            ParentChildSplitter splitter = parentChildSplitterProvider.getIfAvailable();
            if (splitter != null) {
                log.info("根据配置选用父子分级切片器作为默认 DocumentSplitter 门面");
                return splitter;
            }
        } else if (type == AtengAiRagProperties.SplitterType.TABLE) {
            TableSplitter splitter = tableSplitterProvider.getIfAvailable();
            if (splitter != null) {
                log.info("根据配置选用表格感知切片器作为默认 DocumentSplitter 门面");
                return splitter;
            }
        }

        int chunkSize = rag != null && rag.getChunkSize() > 0 ? rag.getChunkSize() : RecursiveCharacterSplitter.DEFAULT_CHUNK_SIZE;
        int chunkOverlap = rag != null && rag.getChunkOverlap() >= 0 && rag.getChunkOverlap() < chunkSize
                ? rag.getChunkOverlap()
                : RecursiveCharacterSplitter.DEFAULT_CHUNK_OVERLAP;
        log.info("自动装配默认原生长文本智能切片器 (RecursiveCharacterSplitter) [chunkSize={}, chunkOverlap={}]",
                chunkSize, chunkOverlap);
        return new RecursiveCharacterSplitter(chunkSize, chunkOverlap);
    }

    /**
     * 自动装配默认纯文本与 Markdown 文档解析器
     *
     * @return TextDocumentParser 实例
     */
    @Bean
    @ConditionalOnMissingBean(TextDocumentParser.class)
    public TextDocumentParser textDocumentParser() {
        log.info("自动装配默认纯文本与 Markdown 文档解析器 (TextDocumentParser)");
        return new TextDocumentParser();
    }

    /**
     * 自动装配默认零臃肿 HTML 超文本解析器
     *
     * @return HtmlDocumentParser 实例
     */
    @Bean
    @ConditionalOnMissingBean(HtmlDocumentParser.class)
    public HtmlDocumentParser htmlDocumentParser() {
        log.info("自动装配默认零臃肿 HTML 超文本文档解析器 (HtmlDocumentParser)");
        return new HtmlDocumentParser();
    }

    /**
     * 自动装配 RAG 多源异构文档解析统一调度门面 DocumentParserManager
     *
     * @param parsersProvider 容器中所有注册的 DocumentParser 提供者
     * @return DocumentParserManager 实例
     */
    @Bean
    @ConditionalOnMissingBean(DocumentParserManager.class)
    public DocumentParserManager documentParserManager(ObjectProvider<DocumentParser> parsersProvider) {
        List<DocumentParser> parsers = parsersProvider.orderedStream().toList();
        log.info("自动装配 RAG 统一文档解析门面 (DocumentParserManager) [已注册解析器数量: {}]", parsers.size());
        return new DocumentParserManager(parsers);
    }

    /**
     * 自动装配 RAG 端到端 ETL Ingestion 摄取管道与生命周期治理门面 (RagIngestionTemplate)
     *
     * @param parserManager        统一文档解析门面
     * @param embeddingTemplate    统一向量化嵌入门面
     * @param documentSplitter     默认文档智能切片门面
     * @param defaultStoreProvider 默认向量存储库提供者 (可选)
     * @param executorProvider     受管异步线程池提供者 (可选)
     * @return RagIngestionTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(RagIngestionTemplate.class)
    public RagIngestionTemplate ragIngestionTemplate(
            DocumentParserManager parserManager,
            EmbeddingTemplate embeddingTemplate,
            DocumentSplitter documentSplitter,
            ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
            ObjectProvider<Executor> executorProvider) {
        log.info("自动装配 RAG 端到端 ETL Ingestion 摄取管道与生命周期治理门面 (RagIngestionTemplate)");
        return new RagIngestionTemplate(
                parserManager,
                embeddingTemplate,
                documentSplitter,
                defaultStoreProvider,
                executorProvider.getIfAvailable()
        );
    }

    /**
     * 自动装配长效情景记忆原子事实抽取器 (MemoryFactExtractor)
     *
     * @param chatModelProvider     大语言模型提供者
     * @param modelRegistryProvider AI 多模型注册中心提供者 (可选)
     * @param properties            AI 根配置属性
     * @param executorProvider      受管异步线程池提供者 (可选)
     * @return MemoryFactExtractor 实例
     */
    @Bean
    @ConditionalOnMissingBean(MemoryFactExtractor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.episodic-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MemoryFactExtractor memoryFactExtractor(
            ObjectProvider<ChatLanguageModel> chatModelProvider,
            ObjectProvider<AiModelRegistry> modelRegistryProvider,
            AtengAiProperties properties,
            ObjectProvider<Executor> executorProvider) {
        log.info("自动装配长效情景记忆原子事实抽取器 (MemoryFactExtractor)");
        return new MemoryFactExtractor(
                chatModelProvider.getIfAvailable(),
                modelRegistryProvider.getIfAvailable(),
                properties.getEpisodicMemory(),
                properties,
                executorProvider.getIfAvailable()
        );
    }

    /**
     * 自动装配跨会话长效情景语义记忆统一治理门面 (EpisodicMemoryTemplate)
     *
     * @param embeddingTemplate     统一向量化嵌入门面
     * @param defaultStoreProvider 默认向量存储库提供者 (可选)
     * @param extractorProvider    长效事实抽取器提供者 (可选)
     * @param properties           AI 根配置属性
     * @param executorProvider     受管异步线程池提供者 (可选)
     * @return EpisodicMemoryTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(EpisodicMemoryTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.episodic-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EpisodicMemoryTemplate episodicMemoryTemplate(
            EmbeddingTemplate embeddingTemplate,
            ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
            ObjectProvider<MemoryFactExtractor> extractorProvider,
            AtengAiProperties properties,
            ObjectProvider<Executor> executorProvider) {
        log.info("自动装配跨会话长效情景语义记忆统一治理门面 (EpisodicMemoryTemplate)");
        return new EpisodicMemoryTemplate(
                embeddingTemplate,
                defaultStoreProvider,
                extractorProvider.getIfAvailable(),
                properties.getEpisodicMemory(),
                executorProvider.getIfAvailable()
        );
    }

    /**
     * 自动装配默认表格感知智能分块切片器 (TableSplitter)
     *
     * @return TableSplitter 实例
     */
    @Bean
    @ConditionalOnMissingBean(TableSplitter.class)
    public TableSplitter tableSplitter() {
        log.info("自动装配默认表格感知分块切片器 (TableSplitter)");
        return new TableSplitter();
    }

    /**
     * 自动装配多模态视觉模型四级解析链 (VisionModelResolver)
     *
     * @param properties            AI 根配置属性
     * @param modelRegistryProvider AI 多模型注册中心提供者
     * @return VisionModelResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean(VisionModelResolver.class)
    public VisionModelResolver visionModelResolver(
            AtengAiProperties properties,
            ObjectProvider<AiModelRegistry> modelRegistryProvider) {
        AtengAiRagProperties rag = properties.getRag();
        AtengAiRagProperties.MultimodalProperties multimodal = rag != null && rag.getParser() != null
                ? rag.getParser().getMultimodal()
                : new AtengAiRagProperties.MultimodalProperties();
        log.info("自动装配多模态视觉大模型解析器 (VisionModelResolver)");
        return new VisionModelResolver(multimodal, modelRegistryProvider.getIfAvailable(), properties);
    }

    /**
     * 自动装配文档图表视觉摘要与扫描件 OCR 增强器 (VisionCaptionEnhancer)
     *
     * @param visionModelResolver 视觉模型解析器
     * @param properties          AI 根配置属性
     * @param uploaderProvider    图片对象存储上传器提供者 (可选)
     * @return VisionCaptionEnhancer 实例
     */
    @Bean
    @ConditionalOnMissingBean(VisionCaptionEnhancer.class)
    public VisionCaptionEnhancer visionCaptionEnhancer(
            VisionModelResolver visionModelResolver,
            AtengAiProperties properties,
            ObjectProvider<VisionCaptionEnhancer.ImageStorageUploader> uploaderProvider) {
        AtengAiRagProperties rag = properties.getRag();
        AtengAiRagProperties.MultimodalProperties multimodal = rag != null && rag.getParser() != null
                ? rag.getParser().getMultimodal()
                : new AtengAiRagProperties.MultimodalProperties();
        log.info("自动装配文档图表视觉摘要与扫描件 OCR 增强器 (VisionCaptionEnhancer) [enabled={}]", multimodal.isEnabled());
        return new VisionCaptionEnhancer(visionModelResolver, multimodal, uploaderProvider.getIfAvailable());
    }

    /**
     * PDF 结构化与多模态文档解析条件配置类 (当检测到 Apache PDFBox 3.x 或 ateng-boot-document 时自适应激活)
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.pdfbox.pdmodel.PDDocument")
    static class PdfParserConfiguration {

        @Bean
        @ConditionalOnMissingBean(PdfDocumentParser.class)
        public PdfDocumentParser pdfDocumentParser(ObjectProvider<VisionCaptionEnhancer> enhancerProvider) {
            log.info("检测到 Apache PDFBox 3.x，自动装配 PDF 结构化与多模态文档解析器 (PdfDocumentParser)");
            return new PdfDocumentParser(enhancerProvider.getIfAvailable());
        }
    }

    /**
     * Word 结构化与多模态文档解析条件配置类 (当检测到 Apache POI / POI-TL 或 ateng-boot-document 时自适应激活)
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.poi.xwpf.usermodel.XWPFDocument")
    static class WordParserConfiguration {

        @Bean
        @ConditionalOnMissingBean(WordDocumentParser.class)
        public WordDocumentParser wordDocumentParser(ObjectProvider<VisionCaptionEnhancer> enhancerProvider) {
            log.info("检测到 Apache POI / POI-TL，自动装配 Word 结构化与多模态文档解析器 (WordDocumentParser)");
            return new WordDocumentParser(enhancerProvider.getIfAvailable());
        }
    }

    /**
     * Excel 结构化文档解析条件配置类 (当检测到 Apache Fesod 或 ateng-boot-excel 时自适应激活)
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.fesod.sheet.FesodSheet")
    static class ExcelParserConfiguration {

        @Bean
        @ConditionalOnMissingBean(ExcelDocumentParser.class)
        public ExcelDocumentParser excelDocumentParser() {
            log.info("检测到 Apache Fesod，自动装配 Excel 结构化文档解析器 (ExcelDocumentParser)");
            return new ExcelDocumentParser();
        }
    }

    /**
     * 对象存储与 RAG 多模态集成条件配置类 (当检测到 ateng-boot-storage 时自适应激活)
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.atengk.boot.storage.template.StorageTemplate")
    @ConditionalOnBean(type = "io.github.atengk.boot.storage.template.StorageTemplate")
    static class StorageIntegrationConfiguration {

        @Bean
        @ConditionalOnMissingBean(VisionCaptionEnhancer.ImageStorageUploader.class)
        public VisionCaptionEnhancer.ImageStorageUploader storageTemplateImageUploader(
                StorageTemplate storageTemplate) {
            log.info("检测到 ateng-boot-storage，自动装配图表对象存储上传适配器 (ImageStorageUploader)");
            return new VisionCaptionEnhancer.ImageStorageUploader() {
                @Override
                public String upload(byte[] imageBytes, String filename) {
                    VisionCaptionEnhancer.StorageUploadResult res = uploadDetail(imageBytes, filename);
                    return res != null ? res.url() : null;
                }

                @Override
                public VisionCaptionEnhancer.StorageUploadResult uploadDetail(byte[] imageBytes, String filename) {
                    String safeName = StringUtils.hasText(filename) ? filename : "image.png";
                    String objectKey = "rag/images/" + UUID.randomUUID() + "_" + safeName;
                    storageTemplate.upload(objectKey, new ByteArrayInputStream(imageBytes));
                    String url = storageTemplate.getUrl(objectKey);
                    return new VisionCaptionEnhancer.StorageUploadResult(objectKey, url);
                }
            };
        }
    }
}
