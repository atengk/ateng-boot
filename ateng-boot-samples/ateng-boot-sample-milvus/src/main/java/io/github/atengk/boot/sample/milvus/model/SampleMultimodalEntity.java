package io.github.atengk.boot.sample.milvus.model;

import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.milvus.v2.common.IndexParam;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 多模态混合特征实体模型 (稠密向量 + 稀疏向量 + 标量倒排 + 动态扩展列)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@MilvusCollection(name = "sample_multimodal", description = "多模态多特征向量集合", ttlSeconds = 604800, mmap = true, replicaNumber = 2)
public class SampleMultimodalEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一标识 ID
     */
    @MilvusId(description = "多模态文档ID")
    private Long id;

    /**
     * 文档标题
     */
    @MilvusField(name = "title", maxLength = 256, description = "文档标题")
    private String title;

    /**
     * 概要信息 (挂载 INVERTED 倒排索引，加速关键字前置过滤)
     */
    @MilvusField(name = "summary", maxLength = 2048, indexType = IndexParam.IndexType.INVERTED, indexName = "idx_summary_inverted", description = "概要摘要倒排索引")
    private String summary;

    /**
     * 语义稠密特征向量 (Dense Vector)
     */
    @MilvusVector(name = "dense_vector", dimension = 4, metricType = IndexParam.MetricType.COSINE, indexType = IndexParam.IndexType.AUTOINDEX, description = "4维语义稠密特征向量")
    private List<Float> denseVector;

    /**
     * 关键字稀疏特征向量 (Sparse Vector: BM25 / SPLADE，维度缺省自动推导 SparseFloatVector)
     */
    @MilvusVector(name = "sparse_vector", metricType = IndexParam.MetricType.IP, indexType = IndexParam.IndexType.SPARSE_INVERTED_INDEX, description = "BM25关键词稀疏特征向量")
    private Map<Long, Float> sparseVector;

    /**
     * 动态未知扩展属性 (透明对接 Milvus $meta 动态列)
     */
    private Map<String, Object> extra;
}
