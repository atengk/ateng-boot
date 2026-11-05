package io.github.atengk.boot.ai.properties;

import io.github.atengk.boot.ai.rag.store.VectorStoreType;
import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI RAG 知识库多向量数据库统一配置属性
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
public class VectorStoreProperties {

    /**
     * 底层向量数据库技术选型实现类型 (默认 AUTO 自动探测与平滑降级)
     */
    private VectorStoreType type = VectorStoreType.AUTO;

    /**
     * Redis 向量存储专属配置
     */
    @NestedConfigurationProperty
    private RedisStoreProperties redis = new RedisStoreProperties();

    /**
     * PostgreSQL pgvector 向量存储专属配置
     */
    @NestedConfigurationProperty
    private PgVectorStoreProperties pgvector = new PgVectorStoreProperties();

    /**
     * Elasticsearch 向量存储专属配置
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
        /**
         * Redis 主机地址 (默认 localhost)
         */
        private String host = "localhost";

        /**
         * Redis 服务端口 (默认 6379)
         */
        private int port = 6379;

        /**
         * Redis 访问认证密码 (可选)
         */
        private String password;

        /**
         * RediSearch 向量索引名称 (默认 rag-index)
         */
        private String indexName = "rag-index";

        /**
         * 写入 Redis 的键前缀 (默认 rag:)
         */
        private String prefix = "rag:";

        /**
         * 向量维度大小 (默认 1536)
         */
        private int dimension = 1536;

        /**
         * 索引与检索保留的元数据字段列表 (默认为常用元数据字段)
         */
        private List<String> metadataKeys = new ArrayList<>(List.of(
                "doc_id", "filename", "doc_name", "doc_hash", "tenant_id",
                "category", "chunk_index", "title", "author", "source", "store"
        ));

        /**
         * 连接与检索超时时间 (默认 10 秒)
         */
        private Duration timeout = Duration.ofSeconds(10);
    }

    /**
     * PostgreSQL pgvector 向量存储专属强类型配置
     */
    @Data
    public static class PgVectorStoreProperties {
        /**
         * PostgreSQL 主机地址 (默认 localhost)
         */
        private String host = "localhost";

        /**
         * PostgreSQL 服务端口 (默认 5432)
         */
        private int port = 5432;

        /**
         * 目标数据库名称 (默认 postgres)
         */
        private String database = "postgres";

        /**
         * 数据库访问用户名 (默认 postgres)
         */
        private String user = "postgres";

        /**
         * 数据库访问密码 (可选)
         */
        private String password;

        /**
         * 存储向量切片的表名称 (默认 rag_embeddings)
         */
        private String table = "rag_embeddings";

        /**
         * 向量维度大小 (默认 1536)
         */
        private int dimension = 1536;

        /**
         * 初始化时是否先删除旧表 (默认 false)
         */
        private boolean dropTableFirst = false;
    }

    /**
     * Elasticsearch 向量存储专属强类型配置
     */
    @Data
    public static class ElasticsearchStoreProperties {
        /**
         * Elasticsearch 服务端完整接口地址 (默认 http://localhost:9200)
         */
        private String serverUrl = "http://localhost:9200";

        /**
         * Elasticsearch API 认证令牌 (可选)
         */
        private String apiKey;

        /**
         * 访问用户名 (可选)
         */
        private String userName;

        /**
         * 访问密码 (可选)
         */
        private String password;

        /**
         * 存储向量切片的索引名称 (默认 rag_embeddings)
         */
        private String indexName = "rag_embeddings";

        /**
         * 向量维度大小 (默认 1536)
         */
        private int dimension = 1536;
    }

    /**
     * Milvus 向量存储专属配置
     */
    @Data
    public static class MilvusStoreProperties {
        /**
         * Milvus gRPC 完整接口地址 (如 http://localhost:19530，配置 uri 时优先采用)
         */
        private String uri;

        /**
         * Milvus 主机地址 (默认 localhost)
         */
        private String host = "localhost";

        /**
         * Milvus gRPC 端口 (默认 19530)
         */
        private int port = 19530;

        /**
         * 访问 Token 或 apiKey (可选)
         */
        private String token;

        /**
         * 向量集合名称 (默认 rag_embeddings)
         */
        private String collectionName = "rag_embeddings";

        /**
         * 向量维度大小 (默认 1536)
         */
        private int dimension = 1536;
    }
}
