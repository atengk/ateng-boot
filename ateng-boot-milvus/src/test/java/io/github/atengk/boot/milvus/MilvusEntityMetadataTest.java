package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milvus 实体模型注解与元数据解析单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusEntityMetadataTest {

    @Data
    @MilvusCollection(name = "test_knowledge_base", description = "知识库问答向量集合", enableDynamicField = true, numShards = 2)
    static class KnowledgeBaseEntity {

        @MilvusId(description = "切片唯一ID", maxLength = 64)
        private String id;

        @MilvusVector(dimension = 1536, metricType = IndexParam.MetricType.COSINE, indexType = IndexParam.IndexType.HNSW, description = "文档语义向量")
        private List<Float> vector;

        @MilvusPartitionKey(maxLength = 64, description = "租户物理分区键")
        private String tenantId;

        @MilvusField(maxLength = 1000, description = "文本切片正文")
        private String content;

        @MilvusField(description = "附加元数据JSON")
        private Map<String, Object> metadata;

        private Integer viewCount;
    }

    @Data
    static class DefaultNamingEntity {

        @MilvusId
        private Long id;

        @MilvusVector(dimension = 768)
        private List<Float> featureVector;
    }

    @Data
    static class MissingIdEntity {
        @MilvusVector(dimension = 128)
        private List<Float> vector;
    }

    @Data
    static class MissingVectorEntity {
        @MilvusId
        private String id;
    }

    @Data
    static class InvalidIdTypeEntity {
        @MilvusId
        private Double id;

        @MilvusVector(dimension = 128)
        private List<Float> vector;
    }

    @Test
    @DisplayName("测试完整注解实体元数据解析")
    void testFullAnnotationMetadataParsing() {
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(KnowledgeBaseEntity.class);

        assertThat(metadata.getCollectionName()).isEqualTo("test_knowledge_base");
        assertThat(metadata.getDescription()).isEqualTo("知识库问答向量集合");
        assertThat(metadata.isEnableDynamicField()).isTrue();
        assertThat(metadata.getNumShards()).isEqualTo(2);

        // 主键字段校验
        MilvusEntityMetadata.FieldMetadata idField = metadata.getIdField();
        assertThat(idField).isNotNull();
        assertThat(idField.getFieldName()).isEqualTo("id");
        assertThat(idField.getDataType()).isEqualTo(DataType.VarChar);
        assertThat(idField.isPrimaryKey()).isTrue();
        assertThat(idField.getMaxLength()).isEqualTo(64);

        // 向量字段校验
        MilvusEntityMetadata.FieldMetadata vectorField = metadata.getVectorField();
        assertThat(vectorField).isNotNull();
        assertThat(vectorField.getFieldName()).isEqualTo("vector");
        assertThat(vectorField.getDataType()).isEqualTo(DataType.FloatVector);
        assertThat(vectorField.isVector()).isTrue();
        assertThat(vectorField.getDimension()).isEqualTo(1536);
        assertThat(vectorField.getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);
        assertThat(vectorField.getIndexType()).isEqualTo(IndexParam.IndexType.HNSW);

        // 分区键字段校验
        MilvusEntityMetadata.FieldMetadata partitionKey = metadata.getPartitionKeyField();
        assertThat(partitionKey).isNotNull();
        assertThat(partitionKey.getFieldName()).isEqualTo("tenantId");
        assertThat(partitionKey.isPartitionKey()).isTrue();

        // 校验 CreateCollectionReq 转换
        CreateCollectionReq req = metadata.toCreateCollectionReq();
        assertThat(req.getCollectionName()).isEqualTo("test_knowledge_base");
        assertThat(req.getCollectionSchema().getFieldSchemaList()).hasSize(6);
    }

    @Test
    @DisplayName("测试默认类名转下划线与默认参数解析")
    void testDefaultNamingAndInference() {
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(DefaultNamingEntity.class);

        assertThat(metadata.getCollectionName()).isEqualTo("default_naming_entity");
        assertThat(metadata.getIdField().getDataType()).isEqualTo(DataType.Int64);
        assertThat(metadata.getVectorField().getFieldName()).isEqualTo("featureVector");
        assertThat(metadata.getVectorField().getDimension()).isEqualTo(768);
        assertThat(metadata.getVectorField().getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);
    }

    @Test
    @DisplayName("测试实体缺少主键时抛出校验异常")
    void testMissingIdException() {
        assertThatThrownBy(() -> MilvusEntityMetadata.of(MissingIdEntity.class))
                .isInstanceOf(MilvusException.class)
                .hasMessageContaining("必须通过 @MilvusId 显式指定主键字段");
    }

    @Test
    @DisplayName("测试实体缺少向量字段时抛出校验异常")
    void testMissingVectorException() {
        assertThatThrownBy(() -> MilvusEntityMetadata.of(MissingVectorEntity.class))
                .isInstanceOf(MilvusException.class)
                .hasMessageContaining("必须通过 @MilvusVector 显式指定向量字段");
    }

    @Test
    @DisplayName("测试实体主键类型非法时抛出异常")
    void testInvalidIdTypeException() {
        assertThatThrownBy(() -> MilvusEntityMetadata.of(InvalidIdTypeEntity.class))
                .isInstanceOf(MilvusException.class)
                .hasMessageContaining("类型只支持 Long (Int64) 或 String (VarChar)");
    }
}
