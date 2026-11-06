package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 长效情景记忆对话抽取并持久化请求传输契约
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待提炼事实的对话历史文本
     */
    @NotBlank(message = "对话历史文本 conversationText 不能为空")
    private String conversationText;

    /**
     * 归属用户标识 (可选，缺省从上下文读取)
     */
    private String userId;

    /**
     * 归属租户标识 (可选，缺省从上下文读取)
     */
    private String tenantId;
}
