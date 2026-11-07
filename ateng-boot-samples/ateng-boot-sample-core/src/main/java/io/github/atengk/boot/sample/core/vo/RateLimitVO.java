package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 接口防刷与流量整形响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端标识
     */
    private String clientId;

    /**
     * 滑动窗口当前计数值
     */
    private long slidingWindowCount;

    /**
     * 滑动窗口是否允许放行
     */
    private boolean slidingWindowAllowed;

    /**
     * 令牌桶是否成功获取到令牌
     */
    private boolean tokenBucketAcquired;

    /**
     * 令牌桶当前剩余可用令牌数
     */
    private double availableTokens;

    /**
     * 幂等防重校验是否通过
     */
    private boolean idempotentPassed;
}
