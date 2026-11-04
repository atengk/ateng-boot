package io.github.atengk.boot.milvus.autoconfigure.langchain4j;

import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.milvus.autoconfigure.AtengMilvusAutoConfiguration;
import io.github.atengk.boot.milvus.langchain4j.MilvusEmbeddingStore;
import io.github.atengk.boot.milvus.properties.MilvusLangChain4jProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Milvus LangChain4j EmbeddingStore 自动装配配置
 * 弱依赖条件装配，仅当类路径存在 LangChain4j EmbeddingStore 且启用时激活
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@AutoConfiguration(after = AtengMilvusAutoConfiguration.class)
@ConditionalOnClass(EmbeddingStore.class)
@ConditionalOnBean(MilvusTemplate.class)
@ConditionalOnProperty(prefix = "ateng.boot.milvus.langchain4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MilvusLangChain4jProperties.class)
public class MilvusLangChain4jAutoConfiguration {

    /**
     * 注册 LangChain4j 官方标准 EmbeddingStore 实现 Bean
     *
     * @param milvusTemplate 核心门面
     * @param properties     配置属性
     * @return MilvusEmbeddingStore 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public MilvusEmbeddingStore milvusEmbeddingStore(MilvusTemplate milvusTemplate, MilvusLangChain4jProperties properties) {
        log.info("初始化 LangChain4j MilvusEmbeddingStore 知识库向量存储 (集合: [{}], 维度: {})",
                properties.getCollectionName(), properties.getDimension());
        return MilvusEmbeddingStore.builder()
                .template(milvusTemplate)
                .collectionName(properties.getCollectionName())
                .vectorFieldName(properties.getVectorFieldName())
                .idFieldName(properties.getIdFieldName())
                .textFieldName(properties.getTextFieldName())
                .tenantFieldName(properties.getTenantFieldName())
                .partitionName(properties.getPartitionName())
                .dimension(properties.getDimension())
                .metricType(properties.getMetricType())
                .indexType(properties.getIndexType())
                .autoCreateCollection(properties.isAutoCreateCollection())
                .consistencyLevel(properties.getConsistencyLevel())
                .build();
    }
}
