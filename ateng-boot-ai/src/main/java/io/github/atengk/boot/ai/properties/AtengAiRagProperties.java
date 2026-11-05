package io.github.atengk.boot.ai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * AI RAG 企业知识库与向量检索强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class AtengAiRagProperties {

    /**
     * 是否启用 RAG 企业知识库与向量化基础设施
     */
    private boolean enabled = true;

    /**
     * 向量召回最大结果数 (Top-K)
     */
    private int maxResults = 3;

    /**
     * 向量召回最低相关度得分阈值 (0.0 ~ 1.0)
     */
    private Double minScore = 0.6;

    /**
     * 默认 Embedding 模型标识名称常量
     */
    public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    /**
     * 默认 Embedding 模型标识名称 (例如 text-embedding-3-small, text-embedding-3-large 等)
     */
    private String modelName = DEFAULT_EMBEDDING_MODEL;

    /**
     * 向量维度 (可选，部分模型如 text-embedding-3 支持指定维度缩减)
     */
    private Integer dimensions;

    /**
     * 默认文档切片分块大小 (字符数，默认 500)
     */
    private int chunkSize = 500;

    /**
     * 默认文档切片分块重叠大小 (字符数，默认 50)
     */
    private int chunkOverlap = 50;

    /**
     * 重排模型 (Reranker) 专属配置
     */
    @NestedConfigurationProperty
    private RerankProperties rerank = new RerankProperties();

    /**
     * Reranker 交叉重排模型强类型配置属性
     */
    @Data
    public static class RerankProperties {
        /**
         * 是否启用 Reranker 交叉重排二次精排
         */
        private boolean enabled = false;

        /**
         * Rerank 服务接口地址 (例如 https://api.siliconflow.cn/v1/rerank 或 http://localhost:8080/rerank)
         */
        private String baseUrl;

        /**
         * Rerank 服务认证令牌 API Key (可选；未配置时继承全局主模型 API Key)
         */
        private String apiKey;

        /**
         * Rerank 模型标识名称 (例如 BAAI/bge-reranker-v2-m3)
         */
        private String modelName = "BAAI/bge-reranker-v2-m3";

        /**
         * 重排调用超时时间
         */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(5);

        /**
         * 最终精排保留的最大结果数 (Top-K)
         */
        private int topK = 3;

        /**
         * 最终精排最低相关度得分阈值 (可选，低于此分数的片段将被过滤)
         */
        private Double minScore;
    }

    /**
     * 独立 Embedding 模型专属配置 (可选；若未单独指定 baseUrl 或 apiKey，则平滑继承全局主模型 ateng.boot.ai.model 的凭据)
     */
    @NestedConfigurationProperty
    private AiModelConfig model = new AiModelConfig();

    /**
     * 解析最终生效的 Embedding 模型配置
     * 若未在 rag.model 中指定 apiKey/baseUrl，平滑回退继承 rootModel 的 apiKey 与 baseUrl
     *
     * @param rootModel 全局主模型配置
     * @return 最终生效的 AiModelConfig 实例，绝不返回 null
     */
    public AiModelConfig resolveEmbeddingConfig(AiModelConfig rootModel) {
        AiModelConfig effective = new AiModelConfig();
        // 1. 先用全局主模型配置填充通用连接与重试参数
        if (rootModel != null) {
            effective.setBaseUrl(rootModel.getBaseUrl());
            effective.setApiKey(rootModel.getApiKey());
            effective.setTimeout(rootModel.getTimeout());
            effective.setMaxRetries(rootModel.getMaxRetries());
            effective.setRetryInitialInterval(rootModel.getRetryInitialInterval());
            effective.setRetryMaxInterval(rootModel.getRetryMaxInterval());
            effective.setRetryMultiplier(rootModel.getRetryMultiplier());
            effective.setLogRequests(rootModel.getLogRequests());
            effective.setLogResponses(rootModel.getLogResponses());
            if (rootModel.getCustomHeaders() != null) {
                effective.getCustomHeaders().putAll(rootModel.getCustomHeaders());
            }
        }

        // 2. 覆盖默认 embedding modelName
        effective.setModelName(this.modelName != null && !this.modelName.isBlank() ? this.modelName : DEFAULT_EMBEDDING_MODEL);

        // 3. 若 rag.model 具备有效配置 (例如显式配置了专属 apiKey 或自定义 baseUrl/modelName)，进行就近覆盖
        if (this.model != null && (this.model.isConfigured() || (this.model.getBaseUrl() != null && !this.model.getBaseUrl().isBlank() && !"https://api.openai.com/v1".equals(this.model.getBaseUrl())))) {
            if (this.model.getBaseUrl() != null && !this.model.getBaseUrl().isBlank()) {
                effective.setBaseUrl(this.model.getBaseUrl());
            }
            if (this.model.getApiKey() != null && !this.model.getApiKey().isBlank()) {
                effective.setApiKey(this.model.getApiKey());
            }
            if (this.model.getModelName() != null && !this.model.getModelName().isBlank()
                    && !AiModelConfig.DEFAULT_MODEL_NAME.equals(this.model.getModelName())) {
                effective.setModelName(this.model.getModelName());
            }
            if (this.model.getTimeout() != null) {
                effective.setTimeout(this.model.getTimeout());
            }
            if (this.model.getMaxRetries() != null) {
                effective.setMaxRetries(this.model.getMaxRetries());
            }
            if (this.model.getRetryInitialInterval() != null) {
                effective.setRetryInitialInterval(this.model.getRetryInitialInterval());
            }
            if (this.model.getRetryMaxInterval() != null) {
                effective.setRetryMaxInterval(this.model.getRetryMaxInterval());
            }
            if (this.model.getRetryMultiplier() != null) {
                effective.setRetryMultiplier(this.model.getRetryMultiplier());
            }
            if (this.model.getLogRequests() != null) {
                effective.setLogRequests(this.model.getLogRequests());
            }
            if (this.model.getLogResponses() != null) {
                effective.setLogResponses(this.model.getLogResponses());
            }
            if (this.model.getCustomHeaders() != null && !this.model.getCustomHeaders().isEmpty()) {
                effective.getCustomHeaders().putAll(this.model.getCustomHeaders());
            }
        }
        return effective;
    }
}
