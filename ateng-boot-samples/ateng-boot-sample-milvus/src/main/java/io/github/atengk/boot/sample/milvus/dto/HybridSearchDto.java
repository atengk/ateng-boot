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
 * 多向量混合检索请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询特征向量
     */
    @NotEmpty(message = "查询特征向量不能为空")
    private List<Float> vector;

    /**
     * 辅助子查询向量 (可选，模拟多向量多路召回)
     */
    private List<Float> secondaryVector;

    /**
     * 召回上限 TopK
     */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 100, message = "topK 最大为 100")
    @Builder.Default
    private Integer topK = 10;

    /**
     * 标量过滤表达式 (可选)
     */
    private String filterExpr;

    /**
     * 重排融合算法类型：RRF 或 WEIGHTED (默认 RRF)
     */
    @Builder.Default
    private String rankerType = "RRF";

    /**
     * RRF 算法平滑参数 k (默认 60)
     */
    @Builder.Default
    private Integer rrfK = 60;

    /**
     * WEIGHTED 算法各路权重列表 (如 [0.7, 0.3])
     */
    private List<Float> weights;
}
