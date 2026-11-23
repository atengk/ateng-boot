package io.github.atengk.boot.realtime.ratelimit;

/**
 * 会话级流量限制裁决结果
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum RateLimitDecision {
    /**
     * 放行
     */
    ALLOWED,
    /**
     * 超限警告并丢弃当前请求帧 (HTTP 429 语义)
     */
    WARNING,
    /**
     * 持续恶意超限，强制切断长连接 (RFC 6455 4005 语义)
     */
    CLOSE
}
