package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文本向量化请求 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagEmbedRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待向量化的明文文本
     */
    @NotBlank(message = "待向量化文本不能为空")
    private String text;
}
