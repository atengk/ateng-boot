package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * AI 内容安全护栏文本审查请求 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGuardrailInspectRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待审查文本正文 (输入提示词或大模型生成输出)
     */
    @NotBlank(message = "审查文本不能为空")
    private String text;

    /**
     * 可选的业务与多租户安全上下文
     */
    @Builder.Default
    private Map<String, Object> context = Collections.emptyMap();
}
