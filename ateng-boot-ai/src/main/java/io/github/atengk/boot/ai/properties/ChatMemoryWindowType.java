package io.github.atengk.boot.ai.properties;

/**
 * AI 会话记忆滑动窗口裁剪策略类型
 *
 * @author Ateng
 * @since 2026-11-04
 */
public enum ChatMemoryWindowType {

    /**
     * 基于消息条数的滑动窗口 (MessageWindowChatMemory)
     * 按消息条数严格修剪，计算开销为零，适合简易文本对话
     */
    MESSAGE,

    /**
     * 基于 Token 估算数量的滑动窗口 (TokenWindowChatMemory)
     * 按上下文 Token 总量动态裁剪早期历史，保底保留首条系统指令，防止长消息击穿大模型窗口
     */
    TOKEN
}
