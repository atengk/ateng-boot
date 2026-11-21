package io.github.atengk.boot.sample.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * RAG 质量按需评测请求 DTO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagEvaluateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户原始提问
     */
    @NotBlank(message = "提问内容 question 不能为空")
    private String question;

    /**
     * 待评测的系统生成回答
     */
    @NotBlank(message = "生成回答 answer 不能为空")
    private String answer;

    /**
     * 检索参考上下文列表
     */
    @JsonAlias("contexts")
    @NotEmpty(message = "检索上下文 retrievedContexts 不能为空")
    private List<String> retrievedContexts;

    /**
     * 可选参考标准答案
     */
    @JsonAlias("groundTruth")
    private String referenceAnswer;

    /**
     * 可选指定评测模型名
     */
    private String modelName;
}
