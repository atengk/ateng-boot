package io.github.atengk.boot.milvus.autoconfigure.langchain4j;

import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus LangChain4j 向量知识库适配强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.milvus.langchain4j")
public class MilvusLangChain4jProperties {

    /**
     * 是否启用 Milvus LangChain4j EmbeddingStore 自动装配
     */
    private boolean enabled = true;

    /**
     * 知识库向量集合名称
     */
    private String collectionName = "langchain4j_embeddings";

    /**
     * 向量存储列名称
     */
    private String vectorFieldName = "vector";

    /**
     * 主键 ID 列名称
     */
    private String idFieldName = "id";

    /**
     * 文本切片内容列名称
     */
    private String textFieldName = "text";

    /**
     * 多租户标识列名称
     */
    private String tenantFieldName = "tenant_id";

    /**
     * 指定的物理分区名称 (可选)
     */
    private String partitionName;

    /**
     * 向量维度大小 (默认 1536，适配 text-embedding-3-small 等主流嵌入模型)
     */
    private int dimension = 1536;

    /**
     * 集合不存在时是否自动创建 Schema 并建立索引预加载
     */
    private boolean autoCreateCollection = true;

    /**
     * 专属读写一致性级别 (可选，默认继承全局配置)
     */
    private MilvusConsistencyLevel consistencyLevel;
}
