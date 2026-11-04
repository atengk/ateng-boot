package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 范围过滤检索 (Range Search) 请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RangeSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询目标集合名称 (可传物理集合名或逻辑别名，为空时默认使用 sample_document)
     */
    private String collectionName;

    /**
     * 查询特征向量 (4维向量)
     */
    @NotEmpty(message = "特征向量不能为空")
    private List<Float> vector;

    /**
     * 相似度/距离阈值外边界 radius (如 COSINE 下大于等于 0.8)
     */
    @NotNull(message = "相似度阈值边界(radius)不能为空")
    private Float radius;

    /**
     * 相似度/距离阈值内边界 rangeFilter (可选，形成类似 [0.5, 0.8) 的范围过滤)
     */
    private Float rangeFilter;

    /**
     * 最大返回条数 (TopK，默认 10)
     */
    @Builder.Default
    private Integer topK = 10;

    /**
     * 标量前置过滤表达式 (可选)
     */
    private String expr;
}
