package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.time.Duration;

/**
 * AI 会话记忆治理强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class ChatMemoryProperties {

    /**
     * 是否启用会话记忆治理基础设施
     */
    private boolean enabled = true;

    /**
     * 会话记忆滑动窗口保留的最大消息条数（默认 20 条，基于消息条数修剪，零分词器开销防上下文溢出）
     */
    private int maxMessages = 20;

    /**
     * 会话存储底座类型，默认为单机内存存储
     */
    private ChatMemoryStoreType type = ChatMemoryStoreType.IN_MEMORY;

    /**
     * Redis 会话存储键前缀，默认为 "ai:chat:memory:"
     */
    private String keyPrefix = "ai:chat:memory:";

    /**
     * Redis 会话记忆 TTL 自动过期淘汰时长，默认为 7 天
     */
    private Duration ttl = Duration.ofDays(7);
}
