package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * RAG 质量多维评测结果响应 VO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagEvaluateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 忠实度评分 (0.0 ~ 1.0)
     */
    private Double faithfulness;

    /**
     * 答案相关性评分 (0.0 ~ 1.0)
     */
    private Double answerRelevance;

    /**
     * 上下文召回率评分 (0.0 ~ 1.0)
     */
    private Double contextRecall;

    /**
     * 综合质量得分 (0.0 ~ 1.0)
     */
    private Double overallScore;

    /**
     * 结构化中文评测依据说明
     */
    private String reasoning;
}
