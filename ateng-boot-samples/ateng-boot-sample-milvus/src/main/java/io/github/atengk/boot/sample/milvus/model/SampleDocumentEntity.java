package io.github.atengk.boot.sample.milvus.model;

import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.milvus.v2.common.IndexParam;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 知识库文档特征实体模型
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@MilvusCollection(name = "sample_document", description = "知识库文档向量集合", numShards = 1)
public class SampleDocumentEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一主键 ID
     */
    @MilvusId(description = "文档主键ID")
    private Long id;

    /**
     * 物理分类分区路由键 (服务端据此哈希路由至物理分区加速过滤)
     */
    @MilvusPartitionKey(maxLength = 64, description = "分类分区路由键")
    private String category;

    /**
     * SaaS 多租户隔离标识
     */
    @MilvusField(name = "tenant_id", maxLength = 64, description = "SaaS多租户标识")
    private String tenantId;

    /**
     * 文档标题
     */
    @MilvusField(name = "title", maxLength = 256, description = "文档标题")
    private String title;

    /**
     * 文档正文
     */
    @MilvusField(name = "content", maxLength = 4096, description = "正文内容")
    private String content;

    /**
     * 4 维稠密特征向量
     */
    @MilvusVector(name = "vector", dimension = 4, metricType = IndexParam.MetricType.COSINE, indexType = IndexParam.IndexType.AUTOINDEX, description = "4维稠密向量")
    private List<Float> vector;
}
