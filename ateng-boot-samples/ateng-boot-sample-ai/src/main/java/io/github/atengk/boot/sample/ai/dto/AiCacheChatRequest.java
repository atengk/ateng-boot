package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 双级语义缓存对话请求 DTO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiCacheChatRequest {

    /**
     * 用户提问文本
     */
    @NotBlank(message = "用户提示词 prompt 不能为空")
    private String prompt;

    /**
     * 可选指定模型标识名称
     */
    private String model;

    /**
     * 可选租户标识（用于隔离验证，未传则沿用请求头上下文）
     */
    private String tenantId;

    /**
     * 是否通过 AiContextHolder.bypassCache() 强制绕过缓存直连大模型
     */
    private boolean bypassCache = false;
}
