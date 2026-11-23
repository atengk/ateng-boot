package io.github.atengk.boot.ai.memory.store;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于并发容器的单机内存会话记忆存储实现
 * <p>
 * 遵循空安全与保底返回铁律，内部采用线程安全的 {@link ConcurrentHashMap} 维护多会话消息快照，
 * 适用于单机轻量级应用、开发联调与单元测试环境。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentMap<Object, List<ChatMessage>> storage = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return Collections.emptyList();
        }
        List<ChatMessage> messages = storage.get(memoryId);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(messages);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }
        storage.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
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
        List<ChatMessage> messages = storage.get(memoryId);
        return messages != null && !messages.isEmpty();
    }

    @Override
    public int getMessageCount(Object memoryId) {
        if (memoryId == null) {
            return 0;
        }
        List<ChatMessage> messages = storage.get(memoryId);
        return messages != null ? messages.size() : 0;
    }

    /**
     * 获取当前内存中纳管的活跃会话总数
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
