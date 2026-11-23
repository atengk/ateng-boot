package io.github.atengk.boot.ai.rag.eval;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 质量按需评测输入请求参数
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class RagEvaluationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户原始提问
     */
    @NotBlank(message = "question 不能为空")
    private String question;

    /**
     * 待评测的 RAG 系统生成回答
     */
    @NotBlank(message = "answer 不能为空")
    private String answer;

    /**
     * 本次生成所依赖的检索上下文片段列表
     */
    @JsonAlias("contexts")
    @NotEmpty(message = "retrievedContexts 检索上下文列表不能为空")
    private List<String> retrievedContexts;

    /**
     * 可选参考标准答案 (用于精准度量 contextRecall 上下文召回率)
     */
    @JsonAlias("groundTruth")
    private String referenceAnswer;

    /**
     * 可选指定评测裁判模型名称 (不指定则使用默认对话模型)
     */
    private String modelName;

    public RagEvaluationRequest() {
    }

    public RagEvaluationRequest(String question,
                                String answer,
                                List<String> retrievedContexts,
                                String referenceAnswer,
                                String modelName) {
        this.question = question;
        this.answer = answer;
        this.retrievedContexts = retrievedContexts != null ? new ArrayList<>(retrievedContexts) : Collections.emptyList();
        this.referenceAnswer = referenceAnswer;
        this.modelName = modelName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getRetrievedContexts() {
        return retrievedContexts;
    }

    public void setRetrievedContexts(List<String> retrievedContexts) {
        this.retrievedContexts = retrievedContexts;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /**
     * RagEvaluationRequest 链式构建器
     */
    public static final class Builder {

        private String question;
        private String answer;
        private List<String> retrievedContexts;
        private String referenceAnswer;
        private String modelName;

        public Builder question(String question) {
            this.question = question;
            return this;
        }

        public Builder answer(String answer) {
            this.answer = answer;
            return this;
        }

        public Builder retrievedContexts(List<String> retrievedContexts) {
            this.retrievedContexts = retrievedContexts;
            return this;
        }

        public Builder referenceAnswer(String referenceAnswer) {
            this.referenceAnswer = referenceAnswer;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public RagEvaluationRequest build() {
            return new RagEvaluationRequest(question, answer, retrievedContexts, referenceAnswer, modelName);
        }
    }
}
