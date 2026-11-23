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
     * 会话记忆滑动窗口裁剪策略 (MESSAGE: 基于消息条数裁剪, TOKEN: 基于 Token 数量裁剪)，默认为 MESSAGE
     */
    private ChatMemoryWindowType windowType = ChatMemoryWindowType.MESSAGE;

    /**
     * 会话记忆基于消息条数裁剪时保留的最大消息条数（默认 20 条，基于消息条数修剪，零分词器开销防上下文溢出）
     */
    private int maxMessages = 20;

    /**
     * 会话记忆基于 Token 窗口裁剪时保留的最大 Token 总量，默认为 8192
     */
    private int maxTokens = 8192;

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

    /**
     * 触发异步提炼摘要的消息条数阈值（当前详细消息条数达到此值时触发异步提炼，默认 20 条）
     */
    private int maxMessagesBeforeSummary = 20;

    /**
     * 触发异步提炼摘要的 Token 数量阈值（当前详细消息累计 Token 估算达到此值时触发异步提炼，默认 4096）
     */
    private int maxTokensBeforeSummary = 4096;

    /**
     * 触发摘要提炼后，保留在详细历史中的最新消息条数（默认 6 条，保证近期交互的高保真度）
     */
    private int keepRecentMessages = 6;

    /**
     * 长期摘要前缀提示语，拼装到双层上下文首条 SystemMessage 中
     */
    private String summaryPrefix = "以下是此前对话的事实摘要约束：\n";

    /**
     * Redis 长会话摘要存储物理键前缀，默认为 "ai:summary:memory:"
     */
    private String summaryKeyPrefix = "ai:summary:memory:";
}
