package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import io.github.atengk.boot.ai.memory.store.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * AI 会话记忆对称管理门面
 * <p>
 * 遵循对称语义完备性铁律，屏蔽底层存储细节，为应用层与运维接口提供完备的会话历史操作能力：
 * <ul>
 *   <li>读写对称：{@link #getMessages(Object)} 与 {@link #updateMessages(Object, List)}</li>
 *   <li>增删对称：{@link #appendMessage(Object, ChatMessage)} 与 {@link #clear(Object)}</li>
 *   <li>生命周期运维：{@link #truncate(Object, int)}、{@link #exists(Object)} 与 {@link #getMessageCount(Object)}</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ChatMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryManager.class);

    private final ChatMemoryStore store;
    private final int maxMessages;

    /**
     * 构造会话记忆管理门面
     *
     * @param store       底层会话存储实现
     * @param maxMessages 默认滑动窗口最大保留消息条数
     */
    public ChatMemoryManager(ChatMemoryStore store, int maxMessages) {
        this.store = Objects.requireNonNull(store, "ChatMemoryStore 不能为空");
        this.maxMessages = maxMessages > 0 ? maxMessages : 20;
    }

    /**
     * 获取指定会话的历史消息列表（保底返回不可变空集合，绝不返回 null）
     *
     * @param memoryId 会话唯一标识
     * @return 历史消息列表副本
     */
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return Collections.emptyList();
        }
        List<ChatMessage> messages = store.getMessages(memoryId);
        return messages != null ? Collections.unmodifiableList(new ArrayList<>(messages)) : Collections.emptyList();
    }

    /**
     * 覆写或全量更新指定会话的消息列表
     *
     * @param memoryId 会话唯一标识
     * @param messages 新的消息列表
     */
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Assert.notNull(memoryId, "memoryId 不能为空");
        if (messages == null || messages.isEmpty()) {
            store.deleteMessages(memoryId);
            return;
        }
        store.updateMessages(memoryId, new ArrayList<>(messages));
        log.debug("[AI-MEMORY-MGR] 会话 [{}] 消息列表已全量更新，当前消息数: {}", memoryId, messages.size());
    }

    /**
     * 向指定会话末尾追加单条消息
     *
     * @param memoryId 会话唯一标识
     * @param message  追加的消息
     */
    public void appendMessage(Object memoryId, ChatMessage message) {
        Assert.notNull(memoryId, "memoryId 不能为空");
        Assert.notNull(message, "追加的消息不能为 null");
        appendMessages(memoryId, Collections.singletonList(message));
    }

    /**
     * 向指定会话末尾批量追加消息
     *
     * @param memoryId 会话唯一标识
     * @param messages 追加的消息列表
     */
    public void appendMessages(Object memoryId, List<ChatMessage> messages) {
        Assert.notNull(memoryId, "memoryId 不能为空");
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<ChatMessage> current = new ArrayList<>(store.getMessages(memoryId));
        current.addAll(messages);

        // 若超出门面默认滑动窗口容量，执行保底修剪
        if (maxMessages > 0 && current.size() > maxMessages) {
            current = current.subList(current.size() - maxMessages, current.size());
        }
        store.updateMessages(memoryId, current);
        log.debug("[AI-MEMORY-MGR] 会话 [{}] 成功追加 {} 条消息，当前消息数: {}", memoryId, messages.size(), current.size());
    }

    /**
     * 判断指定会话是否存在非空记忆
     *
     * @param memoryId 会话唯一标识
     * @return true 若存在
     */
    public boolean exists(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        return store.exists(memoryId);
    }

    /**
     * 获取指定会话的历史消息条数
     *
     * @param memoryId 会话唯一标识
     * @return 消息总数
     */
    public int getMessageCount(Object memoryId) {
        if (memoryId == null) {
            return 0;
        }
        return store.getMessageCount(memoryId);
    }

    /**
     * 对指定会话的历史消息执行截断，仅保留最新的 keepLastMessages 条
     *
     * @param memoryId         会话唯一标识
     * @param keepLastMessages 保留的最晚消息数量
     */
    public void truncate(Object memoryId, int keepLastMessages) {
        if (memoryId == null) {
            return;
        }
        store.truncateMessages(memoryId, keepLastMessages);
        log.debug("[AI-MEMORY-MGR] 会话 [{}] 已截断并保留最新 {} 条消息", memoryId, keepLastMessages);
    }

    /**
     * 按配置的默认窗口大小修剪指定会话的消息
     *
     * @param memoryId 会话唯一标识
     */
    public void truncate(Object memoryId) {
        truncate(memoryId, this.maxMessages);
    }

    /**
     * 清空指定会话的全部历史记忆
     *
     * @param memoryId 会话唯一标识
     */
    public void clear(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        store.clear(memoryId);
        log.debug("[AI-MEMORY-MGR] 会话 [{}] 历史记忆已被清空", memoryId);
    }

    public ChatMemoryStore getStore() {
        return store;
    }

    public int getMaxMessages() {
        return maxMessages;
    }
}
