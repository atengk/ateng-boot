package io.github.atengk.boot.ai.rag.store;

/**
 * AI RAG 知识库向量数据库类型枚举
 *
 * @author Ateng
 * @since 2026-11-05
 */
public enum VectorStoreType {

    /**
     * 自动探测模式 (默认)：优先复用已由外部 Starter (如 ateng-boot-milvus) 或自定义注册的 EmbeddingStore Bean；
     * 若未检测到任何外部向量数据库，平滑降级为单机内存向量存储 (InMemoryEmbeddingStore)
     */
    AUTO,

    /**
     * 单机内存向量存储 (InMemoryEmbeddingStore)，适合本地开发、轻量场景与 100% 离线单元测试
     */
    IN_MEMORY,

    /**
     * 针对分布式 Milvus 向量数据库的专用适配模式
     */
    MILVUS,

    /**
     * 针对 Redis 向量数据库 (RedisSearch / RedisEmbeddingStore) 的专用适配模式
     */
    REDIS,

    /**
     * 针对 PostgreSQL pgvector 扩展的专用适配模式
     */
    PGVECTOR,

    /**
     * 针对 Elasticsearch / OpenSearch 稠密向量存储的专用适配模式
     */
    ELASTICSEARCH,

    /**
     * 自定义模式：框架不自动创建任何默认 EmbeddingStore，交由下游开发者自定义声明
     */
    CUSTOM
}
