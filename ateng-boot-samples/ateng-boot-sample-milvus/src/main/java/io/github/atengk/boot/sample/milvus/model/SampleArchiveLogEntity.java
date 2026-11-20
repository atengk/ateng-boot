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

/**
 * 按月物理分区归档日志实体模型 (演示手动物理分区运维与 auto-create-partition 时序归档)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@MilvusCollection(name = "sample_archive_log", description = "按月物理分区归档日志向量集合", numShards = 1)
public class SampleArchiveLogEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 归档日志主键 ID
     */
    @MilvusId(description = "归档日志主键ID")
    private Long id;

    /**
     * 归档月份标识
     */
    @MilvusField(name = "archive_month", maxLength = 32, description = "归档月份")
    private String archiveMonth;

    /**
     * 日志摘要标题
     */
    @MilvusField(name = "title", maxLength = 256, description = "日志摘要标题")
    private String title;

    /**
     * 4 维日志特征向量
     */
    @MilvusVector(name = "vector", dimension = 4, metricType = IndexParam.MetricType.COSINE, indexType = IndexParam.IndexType.AUTOINDEX, description = "4维日志特征向量")
    private List<Float> vector;
}
