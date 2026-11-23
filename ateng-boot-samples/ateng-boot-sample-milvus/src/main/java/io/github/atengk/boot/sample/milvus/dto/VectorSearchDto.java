package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 单向量相似度检索请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询特征向量
     */
    @NotEmpty(message = "查询向量不能为空")
    private List<Float> vector;

    /**
     * 召回上限 TopK
     */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 100, message = "topK 最大为 100")
    @Builder.Default
    private Integer topK = 10;

    /**
     * 标量过滤表达式 (可选，如 "category == 'AI' and id > 10")
     */
    private String filterExpr;
}
