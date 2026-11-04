package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 多模态稀疏关键词向量检索请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalSparseSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 稀疏关键词权重字典 (termId -> weight)
     */
    @NotEmpty(message = "稀疏特征向量不能为空")
    private Map<Long, Float> sparseVector;

    /**
     * 召回上限 TopK (默认 5)
     */
    @Positive(message = "召回条数必须为正整数")
    @Builder.Default
    private Integer topK = 5;

    /**
     * 标量过滤表达式 (可选)
     */
    private String expr;
}
