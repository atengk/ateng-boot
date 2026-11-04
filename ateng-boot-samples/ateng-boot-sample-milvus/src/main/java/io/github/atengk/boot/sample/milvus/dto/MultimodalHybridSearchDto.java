package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 多模态稠密与稀疏混合多路召回请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalHybridSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 稠密特征向量 (4维语义向量)
     */
    @NotEmpty(message = "稠密特征向量不能为空")
    private List<Float> denseVector;

    /**
     * 稀疏特征向量 (关键词权重字典)
     */
    @NotEmpty(message = "稀疏特征向量不能为空")
    private Map<Long, Float> sparseVector;

    /**
     * 最终全局召回上限 TopK (默认 5)
     */
    @Positive(message = "召回条数必须为正整数")
    @Builder.Default
    private Integer topK = 5;

    /**
     * 标量前置过滤表达式 (可选)
     */
    private String filterExpr;
}
