package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 双级语义缓存对话响应 VO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCacheChatVo {

    /**
     * 大模型或缓存返回的答复正文
     */
    private String reply;

    /**
     * 实际使用的模型标识
     */
    private String model;

    /**
     * 所属租户标识
     */
    private String tenantId;

    /**
     * 是否命中响应缓存（通过 Micrometer hit 计数增量探测）
     */
    private boolean cacheHit;

    /**
     * 本次请求是否强制绕过缓存
     */
    private boolean bypassed;

    /**
     * 当前缓存中活跃条目总数
     */
    private long cacheSize;
}
