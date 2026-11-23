package io.github.atengk.boot.ai.memory.store;

import io.github.atengk.boot.ai.memory.SummaryMemoryState;

/**
 * AI 长会话记忆双层状态存储顶层 SPI 接口
 * <p>
 * 提供长期事实摘要（Summary）与近期详细历史消息（Messages）的高内聚持久化契约，
 * 支持单机内存并发与分布式 Redis 纯净 JSON 双模存储。
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface SummaryChatMemoryStore {

    /**
     * 读取指定会话的长会话记忆双层状态
     *
     * @param memoryId 会话唯一标识
     * @return SummaryMemoryState 实例（保底返回非空空状态，绝不返回 null）
     */
    SummaryMemoryState getState(Object memoryId);

    /**
     * 更新或持久化写入指定会话的双层状态
     *
     * @param memoryId 会话唯一标识
     * @param state    双层状态对象
     */
    void updateState(Object memoryId, SummaryMemoryState state);

    /**
     * 删除指定会话的双层记忆状态
     *
     * @param memoryId 会话唯一标识
     */
    void deleteState(Object memoryId);

    /**
     * 判断指定会话是否存在记忆状态数据
     *
     * @param memoryId 会话唯一标识
     * @return true 若存在非空摘要或非空历史消息
     */
    default boolean exists(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        SummaryMemoryState state = getState(memoryId);
        return state != null && (state.hasSummary() || !state.getMessages().isEmpty());
    }

    /**
     * 清空指定会话的全部记忆
     *
     * @param memoryId 会话唯一标识
     */
    default void clear(Object memoryId) {
        if (memoryId != null) {
            deleteState(memoryId);
        }
    }

    /**
     * 将当前双层存储器适配桥接为基础 {@link ChatMemoryStore} 视图
     *
     * @return 兼容原生 ChatMemoryStore 的视图适配器
     */
    default ChatMemoryStore asChatMemoryStore() {
        return new ChatMemoryStore() {
            @Override
            public java.util.List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
                return getState(memoryId).getMessages();
            }

            @Override
            public void updateMessages(Object memoryId, java.util.List<dev.langchain4j.data.message.ChatMessage> messages) {
                SummaryMemoryState current = getState(memoryId);
                updateState(memoryId, new SummaryMemoryState(current.getSummary(), messages));
            }

            @Override
            public void deleteMessages(Object memoryId) {
                deleteState(memoryId);
            }
        };
    }
}
