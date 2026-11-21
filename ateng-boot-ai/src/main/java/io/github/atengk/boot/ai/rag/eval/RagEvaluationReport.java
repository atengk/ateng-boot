package io.github.atengk.boot.ai.rag.eval;

import java.io.Serializable;

/**
 * RAG 质量多维评测结构化报告 (LLM-as-a-Judge)
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class RagEvaluationReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 忠实度评分 (0.0 ~ 1.0)：衡量生成回答是否严格忠于检索上下文且无幻觉捏造
     */
    private Double faithfulness;

    /**
     * 答案相关性评分 (0.0 ~ 1.0)：衡量生成回答是否准确、完整回应用户问题
     */
    private Double answerRelevance;

    /**
     * 上下文召回率评分 (0.0 ~ 1.0)：衡量检索上下文对标准答案或问题关键要点的覆盖度
     */
    private Double contextRecall;

    /**
     * 综合质量总分 (0.0 ~ 1.0)
     */
    private Double overallScore;

    /**
     * 结构化评测理由与改进建议
     */
    private String reasoning;

    public RagEvaluationReport() {
    }

    public RagEvaluationReport(Double faithfulness,
                               Double answerRelevance,
                               Double contextRecall,
                               Double overallScore,
                               String reasoning) {
        this.faithfulness = faithfulness;
        this.answerRelevance = answerRelevance;
        this.contextRecall = contextRecall;
        this.overallScore = overallScore;
        this.reasoning = reasoning;
    }

    public Double getFaithfulness() {
        return faithfulness;
    }

    public void setFaithfulness(Double faithfulness) {
        this.faithfulness = faithfulness;
    }

    public Double getAnswerRelevance() {
        return answerRelevance;
    }

    public void setAnswerRelevance(Double answerRelevance) {
        this.answerRelevance = answerRelevance;
    }

    public Double getContextRecall() {
        return contextRecall;
    }

    public void setContextRecall(Double contextRecall) {
        this.contextRecall = contextRecall;
    }

    public Double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Double overallScore) {
        this.overallScore = overallScore;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }
}
