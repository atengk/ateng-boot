package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 声明式动态提示词智能体问答请求 DTO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptAgentChatRequest {

    /**
     * 用户提问内容
     */
    @NotBlank(message = "提问内容 question 不能为空")
    private String question;
}
