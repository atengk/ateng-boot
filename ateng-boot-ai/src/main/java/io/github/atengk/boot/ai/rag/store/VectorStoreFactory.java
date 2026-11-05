package io.github.atengk.boot.ai.rag.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * AI RAG 多向量数据库统一适配与动态工厂 (VectorStoreFactory)
 * <p>
 * 支持统一纳管 InMemory, Milvus, Redis, PgVector, Elasticsearch 等向量存储实现，
 * 采用条件反射探测与隔离加载，未引入对应驱动时保持纯净解耦，确保 100% 离线自洽与零冲突运行。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class VectorStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFactory.class);

    public static final String CLASS_REDIS_STORE = "dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore";
    public static final String CLASS_PGVECTOR_STORE = "dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore";
    public static final String CLASS_ELASTICSEARCH_STORE = "dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore";
    public static final String CLASS_MILVUS_STORE = "io.github.atengk.boot.milvus.langchain4j.MilvusEmbeddingStore";
    public static final String CLASS_OFFICIAL_MILVUS_STORE = "dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore";

    private VectorStoreFactory() {
    }

    /**
     * 判断指定驱动类是否存在于当前运行时类路径
     *
     * @param className 全限定类名
     * @return 存在时返回 true
     */
    public static boolean isDriverPresent(String className) {
        return ClassUtils.isPresent(className, VectorStoreFactory.class.getClassLoader());
    }

    /**
     * 根据强类型配置属性创建或平滑回退适配对应的 EmbeddingStore 实例
     *
     * @param properties 向量数据库统一配置属性
     * @return EmbeddingStore 实例
     */
    public static EmbeddingStore<TextSegment> createEmbeddingStore(VectorStoreProperties properties) {
        if (properties == null || properties.getType() == null) {
            log.info("未显式配置向量存储类型，默认创建单机内存向量库 (InMemoryEmbeddingStore)");
            return new InMemoryEmbeddingStore<>();
        }

        VectorStoreType type = properties.getType();
        return switch (type) {
            case IN_MEMORY -> {
                log.info("配置显式指定 IN_MEMORY 模式，创建单机内存向量库 (InMemoryEmbeddingStore)");
                yield new InMemoryEmbeddingStore<>();
            }
            case REDIS -> createRedisStore(properties.getRedis());
            case PGVECTOR -> createPgVectorStore(properties.getPgvector());
            case ELASTICSEARCH -> createElasticsearchStore(properties.getElasticsearch());
            case MILVUS -> createMilvusStore(properties.getMilvus());
            case CUSTOM -> {
                log.info("配置显式指定 CUSTOM 自定义向量库模式，跳过框架默认向量库装配");
                yield null;
            }
            case AUTO -> {
                log.info("AUTO 自动探测模式未命中外部持久化向量库，平滑降级创建单机内存向量库 (InMemoryEmbeddingStore)");
                yield new InMemoryEmbeddingStore<>();
            }
        };
    }

    /**
     * 创建 RedisEmbeddingStore (反射构建，避免强依赖驱动包)
     */
    @SuppressWarnings("unchecked")
    public static EmbeddingStore<TextSegment> createRedisStore(VectorStoreProperties.RedisStoreProperties redisProps) {
        if (!isDriverPresent(CLASS_REDIS_STORE)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "已配置 ateng.boot.ai.rag.store.type=REDIS，但类路径未检测到驱动 [" + CLASS_REDIS_STORE +
                            "]，请引入 langchain4j-community-redis 依赖");
        }
        try {
            Class<?> clazz = ClassUtils.forName(CLASS_REDIS_STORE, VectorStoreFactory.class.getClassLoader());
            Method builderMethod = clazz.getMethod("builder");
            Object builder = builderMethod.invoke(null);

            setBuilderProperty(builder, "host", String.class, redisProps.getHost());
            setBuilderProperty(builder, "port", int.class, redisProps.getPort());
            setBuilderProperty(builder, "indexName", String.class, redisProps.getIndexName());
            setBuilderProperty(builder, "prefixText", String.class, redisProps.getPrefix());
            setBuilderProperty(builder, "dimension", int.class, redisProps.getDimension());
            if (redisProps.getPassword() != null && !redisProps.getPassword().isBlank()) {
                setBuilderProperty(builder, "password", String.class, redisProps.getPassword());
            }

            Method buildMethod = builder.getClass().getMethod("build");
            log.info("成功创建 Redis 知识库向量存储 (RedisEmbeddingStore) [host={}:{}, indexName={}]",
                    redisProps.getHost(), redisProps.getPort(), redisProps.getIndexName());
            return (EmbeddingStore<TextSegment>) buildMethod.invoke(builder);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "构建 Redis 向量存储实例失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 PgVectorEmbeddingStore (反射构建)
     */
    @SuppressWarnings("unchecked")
    public static EmbeddingStore<TextSegment> createPgVectorStore(VectorStoreProperties.PgVectorStoreProperties pgProps) {
        if (!isDriverPresent(CLASS_PGVECTOR_STORE)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "已配置 ateng.boot.ai.rag.store.type=PGVECTOR，但类路径未检测到驱动 [" + CLASS_PGVECTOR_STORE +
                            "]，请引入 langchain4j-pgvector 依赖");
        }
        try {
            Class<?> clazz = ClassUtils.forName(CLASS_PGVECTOR_STORE, VectorStoreFactory.class.getClassLoader());
            Method builderMethod = clazz.getMethod("builder");
            Object builder = builderMethod.invoke(null);

            setBuilderProperty(builder, "host", String.class, pgProps.getHost());
            setBuilderProperty(builder, "port", int.class, pgProps.getPort());
            setBuilderProperty(builder, "database", String.class, pgProps.getDatabase());
            setBuilderProperty(builder, "user", String.class, pgProps.getUser());
            setBuilderProperty(builder, "table", String.class, pgProps.getTable());
            setBuilderProperty(builder, "dimension", int.class, pgProps.getDimension());
            setBuilderProperty(builder, "dropTableFirst", boolean.class, pgProps.isDropTableFirst());
            if (pgProps.getPassword() != null && !pgProps.getPassword().isBlank()) {
                setBuilderProperty(builder, "password", String.class, pgProps.getPassword());
            }

            Method buildMethod = builder.getClass().getMethod("build");
            log.info("成功创建 PostgreSQL pgvector 知识库向量存储 (PgVectorEmbeddingStore) [table={}]", pgProps.getTable());
            return (EmbeddingStore<TextSegment>) buildMethod.invoke(builder);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "构建 PgVector 向量存储实例失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 ElasticsearchEmbeddingStore (反射构建)
     */
    @SuppressWarnings("unchecked")
    public static EmbeddingStore<TextSegment> createElasticsearchStore(VectorStoreProperties.ElasticsearchStoreProperties esProps) {
        if (!isDriverPresent(CLASS_ELASTICSEARCH_STORE)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "已配置 ateng.boot.ai.rag.store.type=ELASTICSEARCH，但类路径未检测到驱动 [" + CLASS_ELASTICSEARCH_STORE +
                            "]，请引入 langchain4j-elasticsearch 依赖");
        }
        try {
            Class<?> clazz = ClassUtils.forName(CLASS_ELASTICSEARCH_STORE, VectorStoreFactory.class.getClassLoader());
            Method builderMethod = clazz.getMethod("builder");
            Object builder = builderMethod.invoke(null);

            setBuilderProperty(builder, "serverUrl", String.class, esProps.getServerUrl());
            setBuilderProperty(builder, "indexName", String.class, esProps.getIndexName());
            setBuilderProperty(builder, "dimension", int.class, esProps.getDimension());
            if (esProps.getApiKey() != null && !esProps.getApiKey().isBlank()) {
                setBuilderProperty(builder, "apiKey", String.class, esProps.getApiKey());
            }
            if (esProps.getUserName() != null && !esProps.getUserName().isBlank()) {
                setBuilderProperty(builder, "userName", String.class, esProps.getUserName());
            }
            if (esProps.getPassword() != null && !esProps.getPassword().isBlank()) {
                setBuilderProperty(builder, "password", String.class, esProps.getPassword());
            }

            Method buildMethod = builder.getClass().getMethod("build");
            log.info("成功创建 Elasticsearch 知识库向量存储 (ElasticsearchEmbeddingStore) [serverUrl={}, indexName={}]",
                    esProps.getServerUrl(), esProps.getIndexName());
            return (EmbeddingStore<TextSegment>) buildMethod.invoke(builder);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "构建 Elasticsearch 向量存储实例失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建或探测 MilvusEmbeddingStore
     */
    @SuppressWarnings("unchecked")
    public static EmbeddingStore<TextSegment> createMilvusStore(VectorStoreProperties.MilvusStoreProperties milvusProps) {
        // 优先检查官方 Milvus 驱动
        if (isDriverPresent(CLASS_OFFICIAL_MILVUS_STORE)) {
            try {
                Class<?> clazz = ClassUtils.forName(CLASS_OFFICIAL_MILVUS_STORE, VectorStoreFactory.class.getClassLoader());
                Method builderMethod = clazz.getMethod("builder");
                Object builder = builderMethod.invoke(null);
                setBuilderProperty(builder, "collectionName", String.class, milvusProps.getCollectionName());
                setBuilderProperty(builder, "dimension", int.class, milvusProps.getDimension());
                Method buildMethod = builder.getClass().getMethod("build");
                log.info("成功通过官方驱动创建 MilvusEmbeddingStore [collection={}]", milvusProps.getCollectionName());
                return (EmbeddingStore<TextSegment>) buildMethod.invoke(builder);
            } catch (Exception e) {
                log.warn("通过官方驱动创建 Milvus 失败: {}", e.getMessage());
            }
        }
        throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                "已配置 ateng.boot.ai.rag.store.type=MILVUS，请引入 ateng-boot-milvus 或 langchain4j-milvus 依赖");
    }

    private static void setBuilderProperty(Object builder, String methodName, Class<?> paramType, Object value) {
        if (builder == null || value == null) {
            return;
        }
        try {
            Method m = builder.getClass().getMethod(methodName, paramType);
            m.invoke(builder, value);
        } catch (NoSuchMethodException ignored) {
            // builder 可能不支持某些属性，静默跳过
        } catch (Exception e) {
            log.debug("设置向量库 Builder 属性 [{}] 失败: {}", methodName, e.getMessage());
        }
    }
}
