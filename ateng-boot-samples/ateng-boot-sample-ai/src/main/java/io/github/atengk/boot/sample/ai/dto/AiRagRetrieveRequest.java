package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库向量相似度检索请求 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagRetrieveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 检索问题或关键字
     */
    @NotBlank(message = "检索关键字/问题不能为空")
    private String query;
}
