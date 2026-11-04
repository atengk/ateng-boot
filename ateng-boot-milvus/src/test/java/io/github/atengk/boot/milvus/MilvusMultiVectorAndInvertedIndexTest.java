package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.model.MilvusEntityConverter;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 实体多向量映射、稀疏向量自动推导与标量倒排索引全链路测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusMultiVectorAndInvertedIndexTest {

    private MilvusClientV2 client;
    private AtengMilvusProperties properties;
    private MilvusTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        template = new MilvusTemplate(client, properties);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "hybrid_doc_collection", description = "多向量与标量倒排索引测试集合")
    public static class HybridArticleEntity {

        @MilvusId
        private Long id;

        @MilvusField(indexType = IndexParam.IndexType.INVERTED, indexName = "idx_article_content", description = "文本全文倒排索引")
        private String content;

        @MilvusField(indexType = IndexParam.IndexType.STL_SORT)
        private Integer readCount;

        @MilvusVector(name = "dense_vec", dimension = 1536, metricType = IndexParam.MetricType.COSINE)
        private List<Float> denseVector;

        @MilvusVector(name = "sparse_vec") // 缺省 dimension，类型为 SortedMap 自动推导为 SparseFloatVector
        private SortedMap<Long, Float> sparseVector;
    }

    @Test
    @DisplayName("元数据推导 - 多向量映射与稀疏向量特征解析")
    void testMultiVectorAndSparseMetadataInference() {
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(HybridArticleEntity.class);

        assertThat(metadata.getCollectionName()).isEqualTo("hybrid_doc_collection");
        assertThat(metadata.getVectorFields()).hasSize(2);

        // 主向量 / 稠密向量
        MilvusEntityMetadata.FieldMetadata denseFm = metadata.getVectorFields().get(0);
        assertThat(denseFm.getFieldName()).isEqualTo("dense_vec");
        assertThat(denseFm.getDataType()).isEqualTo(DataType.FloatVector);
        assertThat(denseFm.getDimension()).isEqualTo(1536);
        assertThat(denseFm.getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);

        // 稀疏向量
        MilvusEntityMetadata.FieldMetadata sparseFm = metadata.getVectorFields().get(1);
        assertThat(sparseFm.getFieldName()).isEqualTo("sparse_vec");
        assertThat(sparseFm.getDataType()).isEqualTo(DataType.SparseFloatVector);
        assertThat(sparseFm.getDimension()).isEqualTo(-1);
        assertThat(sparseFm.getMetricType()).isEqualTo(IndexParam.MetricType.IP);
        assertThat(sparseFm.getIndexType()).isEqualTo(IndexParam.IndexType.SPARSE_INVERTED_INDEX);

        // 标量索引
        List<MilvusEntityMetadata.FieldMetadata> scalars = metadata.getScalarFields();
        assertThat(scalars).hasSize(2);

        MilvusEntityMetadata.FieldMetadata contentFm = scalars.stream()
                .filter(s -> "content".equals(s.getFieldName())).findFirst().orElseThrow();
        assertThat(contentFm.getIndexType()).isEqualTo(IndexParam.IndexType.INVERTED);
        assertThat(contentFm.getIndexName()).isEqualTo("idx_article_content");

        MilvusEntityMetadata.FieldMetadata readCountFm = scalars.stream()
                .filter(s -> "readCount".equals(s.getFieldName())).findFirst().orElseThrow();
        assertThat(readCountFm.getIndexType()).isEqualTo(IndexParam.IndexType.STL_SORT);
        assertThat(readCountFm.getIndexName()).isEqualTo("idx_readCount");
    }

    @Test
    @DisplayName("Schema生成 - 多向量与标量FieldSchema定义校验")
    void testFieldSchemaGeneration() {
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(HybridArticleEntity.class);
        CreateCollectionReq req = metadata.toCreateCollectionReq();

        assertThat(req.getCollectionName()).isEqualTo("hybrid_doc_collection");
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = req.getCollectionSchema().getFieldSchemaList();
        assertThat(fieldSchemaList).hasSize(5);

        // 验证稀疏向量未传递正数维度
        CreateCollectionReq.FieldSchema sparseSchema = fieldSchemaList.stream()
                .filter(f -> "sparse_vec".equals(f.getName())).findFirst().orElseThrow();
        assertThat(sparseSchema.getDataType()).isEqualTo(DataType.SparseFloatVector);
        assertThat(sparseSchema.getDimension()).isNull();

        // 验证稠密向量保留 1536 维度
        CreateCollectionReq.FieldSchema denseSchema = fieldSchemaList.stream()
                .filter(f -> "dense_vec".equals(f.getName())).findFirst().orElseThrow();
        assertThat(denseSchema.getDataType()).isEqualTo(DataType.FloatVector);
        assertThat(denseSchema.getDimension()).isEqualTo(1536);
    }

    @Test
    @DisplayName("createCollectionAndIndex - 原子化创建双向量索引与标量倒排索引")
    void testCreateCollectionAndIndexAtomicFlow() {
        when(client.hasCollection(any())).thenReturn(false);
        when(client.listIndexes(any(ListIndexesReq.class))).thenReturn(Collections.emptyList());
        when(client.getLoadState(any())).thenReturn(false);

        template.createCollectionAndIndex(HybridArticleEntity.class);

        // 1. 验证创建集合
        ArgumentCaptor<CreateCollectionReq> collCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(client).createCollection(collCaptor.capture());
        assertThat(collCaptor.getValue().getCollectionName()).isEqualTo("hybrid_doc_collection");

        // 2. 验证创建索引次数 (稠密向量 + 稀疏向量 + content倒排 + readCount排序 = 4次)
        ArgumentCaptor<CreateIndexReq> indexCaptor = ArgumentCaptor.forClass(CreateIndexReq.class);
        verify(client, times(4)).createIndex(indexCaptor.capture());
        List<CreateIndexReq> createdIndexes = indexCaptor.getAllValues();

        // 验证 dense_vec 向量索引
        assertThat(createdIndexes).anySatisfy(idx -> {
            IndexParam param = idx.getIndexParams().get(0);
            assertThat(param.getFieldName()).isEqualTo("dense_vec");
            assertThat(param.getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);
        });

        // 验证 sparse_vec 向量索引
        assertThat(createdIndexes).anySatisfy(idx -> {
            IndexParam param = idx.getIndexParams().get(0);
            assertThat(param.getFieldName()).isEqualTo("sparse_vec");
            assertThat(param.getMetricType()).isEqualTo(IndexParam.MetricType.IP);
            assertThat(param.getIndexType()).isEqualTo(IndexParam.IndexType.SPARSE_INVERTED_INDEX);
        });

        // 验证 content 标量倒排索引
        assertThat(createdIndexes).anySatisfy(idx -> {
            IndexParam param = idx.getIndexParams().get(0);
            assertThat(param.getFieldName()).isEqualTo("content");
            assertThat(param.getIndexType()).isEqualTo(IndexParam.IndexType.INVERTED);
            assertThat(param.getIndexName()).isEqualTo("idx_article_content");
        });

        // 验证 readCount 标量排序索引
        assertThat(createdIndexes).anySatisfy(idx -> {
            IndexParam param = idx.getIndexParams().get(0);
            assertThat(param.getFieldName()).isEqualTo("readCount");
            assertThat(param.getIndexType()).isEqualTo(IndexParam.IndexType.STL_SORT);
        });

        // 3. 验证加载至内存
        verify(client).loadCollection(any());
    }

    @Test
    @DisplayName("实体双向转换 - 多向量与稀疏向量JSON序列化与反序列化")
    void testEntityBidirectionalConversionWithSparseVector() {
        SortedMap<Long, Float> sparseMap = new TreeMap<>();
        sparseMap.put(1001L, 0.85f);
        sparseMap.put(2002L, 1.25f);

        HybridArticleEntity entity = HybridArticleEntity.builder()
                .id(999L)
                .content("BGE-M3 混合嵌入与倒排索引")
                .readCount(120)
                .denseVector(List.of(0.1f, 0.2f, 0.3f))
                .sparseVector(sparseMap)
                .build();

        // 实体转 JsonObject
        var jsonObject = MilvusEntityConverter.entityToJsonObject(entity);
        assertThat(jsonObject.get("id").getAsLong()).isEqualTo(999L);
        assertThat(jsonObject.has("dense_vec")).isTrue();
        assertThat(jsonObject.has("sparse_vec")).isTrue();

        // 原始 Map 反序列化回实体
        Map<String, Object> rawData = new HashMap<>();
        rawData.put("id", 999L);
        rawData.put("content", "BGE-M3 混合嵌入与倒排索引");
        rawData.put("readCount", 120);
        rawData.put("dense_vec", List.of(0.1f, 0.2f, 0.3f));
        rawData.put("sparse_vec", Map.of("1001", 0.85f, "2002", 1.25f));

        HybridArticleEntity converted = MilvusEntityConverter.mapToEntity(rawData, HybridArticleEntity.class);
        assertThat(converted).isNotNull();
        assertThat(converted.getId()).isEqualTo(999L);
        assertThat(converted.getContent()).isEqualTo("BGE-M3 混合嵌入与倒排索引");
        assertThat(converted.getDenseVector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(converted.getSparseVector()).containsEntry(1001L, 0.85f).containsEntry(2002L, 1.25f);
    }
}
