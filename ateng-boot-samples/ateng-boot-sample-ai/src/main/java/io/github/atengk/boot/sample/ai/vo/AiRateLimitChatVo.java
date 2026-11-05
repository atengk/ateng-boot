package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微观突发滑动限流对话体验响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRateLimitChatVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始输入提示词
     */
    private String prompt;

    /**
     * 模型回答正文
     */
    private String reply;

    /**
     * 执行大模型名称
     */
    private String modelName;

    /**
     * 业务租户标识
     */
    private String tenantId;

    /**
     * 本次调用评估 Token 数量
     */
    private Integer tokensUsed;

    /**
     * 限流决策通过状态
     */
    private boolean allowed;

    /**
     * 状态说明消息
     */
    private String message;
}
