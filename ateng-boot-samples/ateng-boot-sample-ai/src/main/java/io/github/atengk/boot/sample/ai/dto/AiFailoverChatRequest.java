package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多模型故障自动转移容灾对话请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFailoverChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户提问内容
     */
    @NotBlank(message = "对话输入内容不能为空")
    private String prompt;

    /**
     * 首选主模型名称 (可选，默认 "default")
     */
    @Builder.Default
    private String primaryModel = "default";

    /**
     * 备选容灾模型名称 (可选，默认 "fallback")
     */
    @Builder.Default
    private String fallbackModel = "fallback";

    /**
     * 是否模拟主模型不可用以触发自动转移演示
     */
    @Builder.Default
    private Boolean simulatePrimaryFailure = false;
}
