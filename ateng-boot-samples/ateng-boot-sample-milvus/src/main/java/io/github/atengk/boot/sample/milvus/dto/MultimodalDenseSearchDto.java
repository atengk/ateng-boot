package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 多模态稠密语义向量检索请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalDenseSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询特征向量 (4维向量)
     */
    @NotEmpty(message = "查询向量不能为空")
    private List<Float> vector;

    /**
     * 召回上限 TopK (默认 5)
     */
    @Positive(message = "召回条数必须为正整数")
    @Builder.Default
    private Integer topK = 5;

    /**
     * 标量过滤表达式 (可选，如 summary LIKE '%向量%')
     */
    private String expr;
}
