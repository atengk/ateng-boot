package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微观突发滑动限流与配额尝试响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRateLimitVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否允许通过当前请求
     */
    private boolean allowed;

    /**
     * 当前评估的租户或 API Key 标识
     */
    private String clientKey;

    /**
     * 剩余可用 Token 配额 (若可获知，负数或 null 表示未知)
     */
    private Long remainingTokens;

    /**
     * 若被限流，建议重试等待毫秒数
     */
    private Long retryAfterMs;

    /**
     * 状态提示说明
     */
    private String message;
}
