package io.github.atengk.boot.ai.memory.store;

import io.github.atengk.boot.ai.memory.SummaryMemoryState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于并发容器的单机内存长会话记忆双层状态存储实现
 * <p>
 * 遵循空安全与保底返回铁律，内部采用线程安全的 {@link ConcurrentHashMap} 维护状态快照，
 * 适用于单机轻量级应用、开发联调与单元测试环境。
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class InMemorySummaryChatMemoryStore implements SummaryChatMemoryStore {

    private final ConcurrentMap<Object, SummaryMemoryState> storage = new ConcurrentHashMap<>();

    @Override
    public SummaryMemoryState getState(Object memoryId) {
        if (memoryId == null) {
            return new SummaryMemoryState();
        }
        SummaryMemoryState state = storage.get(memoryId);
        return state != null ? state : new SummaryMemoryState();
    }

    @Override
    public void updateState(Object memoryId, SummaryMemoryState state) {
        if (memoryId == null) {
            return;
        }
        if (state == null || (!state.hasSummary() && state.getMessages().isEmpty())) {
            deleteState(memoryId);
            return;
        }
        storage.put(memoryId, state);
    }

    @Override
    public void deleteState(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        storage.remove(memoryId);
    }

    @Override
    public boolean exists(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        SummaryMemoryState state = storage.get(memoryId);
        return state != null && (state.hasSummary() || !state.getMessages().isEmpty());
    }

    /**
     * 获取当前内存中活跃的长会话总数
     *
     * @return 活跃会话数
     */
    public int sessionCount() {
        return storage.size();
    }

    /**
     * 清空全部会话记忆
     */
    public void clearAll() {
        storage.clear();
    }
}
