package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式保底 Token 估算响应视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStreamFallbackVo {

    /**
     * 用户提示词
     */
    private String prompt;

    /**
     * 模拟模型流式吐出的正文
     */
    private String responseText;

    /**
     * 预估的输入 Token 数量
     */
    private int promptTokens;

    /**
     * 预估的输出 Token 数量
     */
    private int completionTokens;

    /**
     * 预估的总 Token 数量
     */
    private int totalTokens;
}
