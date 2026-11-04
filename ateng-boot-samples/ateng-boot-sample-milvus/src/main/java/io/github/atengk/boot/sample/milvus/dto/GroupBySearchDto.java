package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 单向量 GroupBy 聚合检索请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupBySearchDto implements Serializable {

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
     * GroupBy 去重聚合的标量字段名称 (如 category)
     */
    @NotBlank(message = "聚合分组字段不能为空")
    private String groupByField;

    /**
     * 返回最大聚合结果组数 (TopK，默认 5)
     */
    @Builder.Default
    private Integer topK = 5;

    /**
     * 标量前置过滤表达式 (可选)
     */
    private String expr;
}
