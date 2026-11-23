package io.github.atengk.boot.ai.memory.store;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * AI 会话记忆存储顶层 SPI 接口
 * <p>
 * 继承 LangChain4j 原生 {@link dev.langchain4j.store.memory.chat.ChatMemoryStore}，
 * 扩展对称运维方法（探测、消息计数、对称清空与轮次截断），提供高内聚且自洽的会话治理契约。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface ChatMemoryStore extends dev.langchain4j.store.memory.chat.ChatMemoryStore {

    /**
     * 判断指定会话是否存在记忆数据
     *
     * @param memoryId 会话唯一标识
     * @return true 若存在非空历史消息
     */
    default boolean exists(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        List<ChatMessage> messages = getMessages(memoryId);
        return messages != null && !messages.isEmpty();
    }

    /**
     * 获取指定会话的历史消息总条数
     *
     * @param memoryId 会话唯一标识
     * @return 历史消息条数
     */
    default int getMessageCount(Object memoryId) {
        if (memoryId == null) {
            return 0;
        }
        List<ChatMessage> messages = getMessages(memoryId);
        return messages != null ? messages.size() : 0;
    }

    /**
     * 清空指定会话的全部历史记忆
     *
     * @param memoryId 会话唯一标识
     */
    default void clear(Object memoryId) {
        if (memoryId != null) {
            deleteMessages(memoryId);
        }
    }

    /**
     * 对指定会话的历史消息执行截断，仅保留最新的 keepLastMessages 条消息
     *
     * @param memoryId         会话唯一标识
     * @param keepLastMessages 保留的最晚消息数量
     */
    default void truncateMessages(Object memoryId, int keepLastMessages) {
        if (memoryId == null) {
            return;
        }
        if (keepLastMessages <= 0) {
            deleteMessages(memoryId);
            return;
        }
        List<ChatMessage> current = getMessages(memoryId);
        if (current == null || current.size() <= keepLastMessages) {
            return;
        }
        List<ChatMessage> truncated = current.subList(current.size() - keepLastMessages, current.size());
        updateMessages(memoryId, truncated);
    }
}
