package io.github.atengk.boot.ai.rag.store;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * AI RAG 知识库多向量数据库统一配置属性
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
public class VectorStoreProperties {

    /**
     * 目标向量数据库类型 (默认 AUTO 自动探测并平滑回退)
     */
    private VectorStoreType type = VectorStoreType.AUTO;

    /**
     * Redis 向量数据库专属配置
     */
    @NestedConfigurationProperty
    private RedisStoreProperties redis = new RedisStoreProperties();

    /**
     * PostgreSQL pgvector 专属配置
     */
    @NestedConfigurationProperty
    private PgVectorStoreProperties pgvector = new PgVectorStoreProperties();

    /**
     * Elasticsearch 向量数据库专属配置
     */
    @NestedConfigurationProperty
    private ElasticsearchStoreProperties elasticsearch = new ElasticsearchStoreProperties();

    /**
     * Milvus 向量数据库专属配置
     */
    @NestedConfigurationProperty
    private MilvusStoreProperties milvus = new MilvusStoreProperties();

    /**
     * Redis 向量存储专属强类型配置
     */
    @Data
    public static class RedisStoreProperties {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private String indexName = "rag-index";
        private String prefix = "rag:";
        private int dimension = 1536;
        private Duration timeout = Duration.ofSeconds(10);
    }

    /**
     * PostgreSQL pgvector 向量存储专属强类型配置
     */
    @Data
    public static class PgVectorStoreProperties {
        private String host = "localhost";
        private int port = 5432;
        private String database = "postgres";
        private String user = "postgres";
        private String password;
        private String table = "rag_embeddings";
        private int dimension = 1536;
        private boolean dropTableFirst = false;
    }

    /**
     * Elasticsearch 向量存储专属强类型配置
     */
    @Data
    public static class ElasticsearchStoreProperties {
        private String serverUrl = "http://localhost:9200";
        private String apiKey;
        private String userName;
        private String password;
        private String indexName = "rag_embeddings";
        private int dimension = 1536;
    }

    /**
     * Milvus 向量存储专属强类型配置
     */
    @Data
    public static class MilvusStoreProperties {
        private String collectionName = "langchain4j_embeddings";
        private int dimension = 1536;
    }
}
