package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * RAG 知识库问答增强请求 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagAskRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户提问
     */
    @NotBlank(message = "问答问题不能为空")
    private String question;
}
