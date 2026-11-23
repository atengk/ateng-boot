package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 长会话异步摘要对话请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话唯一标识 (用于跨轮次状态关联)
     */
    @NotBlank(message = "会话 ID 不能为空")
    private String sessionId;

    /**
     * 用户本轮输入消息
     */
    @NotBlank(message = "用户输入消息不能为空")
    private String message;
}
