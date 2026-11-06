package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 智能体长效情景记忆对话请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class AgentEpisodicChatRequest implements Serializable {

    /**
     * 目标用户标识（可选，若未指定则自动回退至上下文或会话标识）
     */
    private String userId;

    /**
     * 用户会话标识（可选）
     */
    private String sessionId;

    /**
     * 用户对话问询消息内容
     */
    @NotBlank(message = "消息内容 message 不能为空")
    private String message;
}
