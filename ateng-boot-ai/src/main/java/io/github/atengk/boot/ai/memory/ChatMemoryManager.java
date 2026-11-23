package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.Tokenizer;
import io.github.atengk.boot.ai.memory.store.ChatMemoryStore;
import io.github.atengk.boot.ai.properties.ChatMemoryProperties;
import io.github.atengk.boot.ai.properties.ChatMemoryWindowType;
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
 *   <li>生命周期运维：{@link #truncate(Object, int)}、{@link #truncateByTokens(Object, int)}、{@link #exists(Object)} 与 {@link #getMessageCount(Object)}</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ChatMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryManager.class);

    private final ChatMemoryStore store;
    private final int maxMessages;
    private final int maxTokens;
    private final ChatMemoryWindowType windowType;
    private final Tokenizer tokenizer;

    /**
     * 兼容性构造方法：按消息条数滑动窗口管理会话记忆
     *
     * @param store       底层会话存储实现
     * @param maxMessages 默认滑动窗口最大保留消息条数
     */
    public ChatMemoryManager(ChatMemoryStore store, int maxMessages) {
        this(store, createDefaultProperties(maxMessages), null);
    }

    /**
     * 全特性构造方法：支持消息条数或 Token 上限双滑动窗口策略
     *
     * @param store      底层会话存储实现
     * @param properties 会话记忆配置属性
     * @param tokenizer  分词器实现 (若为空则自动使用启发式保底分词器)
     */
    public ChatMemoryManager(ChatMemoryStore store, ChatMemoryProperties properties, Tokenizer tokenizer) {
        this.store = Objects.requireNonNull(store, "ChatMemoryStore 不能为空");
        ChatMemoryProperties props = properties != null ? properties : new ChatMemoryProperties();
        this.maxMessages = props.getMaxMessages() > 0 ? props.getMaxMessages() : 20;
        this.maxTokens = props.getMaxTokens() > 0 ? props.getMaxTokens() : 8192;
        this.windowType = props.getWindowType() != null ? props.getWindowType() : ChatMemoryWindowType.MESSAGE;
        this.tokenizer = tokenizer != null ? tokenizer : new HeuristicTokenizer();
    }

    private static ChatMemoryProperties createDefaultProperties(int maxMessages) {
        ChatMemoryProperties props = new ChatMemoryProperties();
        props.setMaxMessages(maxMessages > 0 ? maxMessages : 20);
        props.setWindowType(ChatMemoryWindowType.MESSAGE);
        return props;
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
     * 向指定会话末尾批量追加消息，并根据配置的窗口类型（MESSAGE / TOKEN）自动执行 FIFO 滑动裁剪
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

        // 根据配置的窗口类型执行保底修剪防爆
        if (windowType == ChatMemoryWindowType.TOKEN) {
            current = truncateMessagesToTokenLimit(current, maxTokens, tokenizer);
        } else if (maxMessages > 0 && current.size() > maxMessages) {
            current = current.subList(current.size() - maxMessages, current.size());
        }

        store.updateMessages(memoryId, current);
        log.debug("[AI-MEMORY-MGR] 会话 [{}] 成功追加 {} 条消息，当前保留消息数: {}", memoryId, messages.size(), current.size());
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
     * 估算指定会话历史记忆当前累计消耗的 Token 总量
     *
     * @param memoryId 会话唯一标识
     * @return 估算的 Token 总数
     */
    public int getTokenCount(Object memoryId) {
        if (memoryId == null) {
            return 0;
        }
        List<ChatMessage> messages = store.getMessages(memoryId);
        return tokenizer.estimateTokenCountInMessages(messages);
    }

    /**
     * 对指定会话的历史消息执行条数截断，仅保留最新的 keepLastMessages 条
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
     * 按配置的默认消息条数窗口大小修剪指定会话的消息
     *
     * @param memoryId 会话唯一标识
     */
    public void truncate(Object memoryId) {
        truncate(memoryId, this.maxMessages);
    }

    /**
     * 按配置的默认 Token 上限自动修剪指定会话的消息
     *
     * @param memoryId 会话唯一标识
     */
    public void truncateByTokens(Object memoryId) {
        truncateByTokens(memoryId, this.maxTokens);
    }

    /**
     * 按指定 Token 上限修剪会话历史消息（先进先出淘汰早期非系统消息，保留首条 SystemMessage）
     *
     * @param memoryId       会话唯一标识
     * @param maxTokensLimit 最大保留 Token 数量
     */
    public void truncateByTokens(Object memoryId, int maxTokensLimit) {
        if (memoryId == null || maxTokensLimit <= 0) {
            return;
        }
        List<ChatMessage> messages = store.getMessages(memoryId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<ChatMessage> truncated = truncateMessagesToTokenLimit(messages, maxTokensLimit, tokenizer);
        if (truncated.size() != messages.size()) {
            store.updateMessages(memoryId, truncated);
            log.debug("[AI-MEMORY-MGR] 会话 [{}] 基于 Token 修剪完成: 原有 {} 条 -> 保留 {} 条 (上限 {} tokens)",
                    memoryId, messages.size(), truncated.size(), maxTokensLimit);
        }
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

    public int getMaxTokens() {
        return maxTokens;
    }

    public ChatMemoryWindowType getWindowType() {
        return windowType;
    }

    public Tokenizer getTokenizer() {
        return tokenizer;
    }

    private static List<ChatMessage> truncateMessagesToTokenLimit(List<ChatMessage> messages, int maxTokensLimit, Tokenizer tokenizer) {
        if (messages == null || messages.isEmpty() || maxTokensLimit <= 0) {
            return Collections.emptyList();
        }
        List<ChatMessage> list = new ArrayList<>(messages);
        int totalTokens = tokenizer.estimateTokenCountInMessages(list);
        if (totalTokens <= maxTokensLimit) {
            return list;
        }

        boolean hasSystemMessage = !list.isEmpty() && list.get(0) instanceof SystemMessage;
        while (totalTokens > maxTokensLimit && list.size() > (hasSystemMessage ? 1 : 0)) {
            int removeIndex = hasSystemMessage ? 1 : 0;
            if (removeIndex >= list.size()) {
                break;
            }
            ChatMessage removed = list.remove(removeIndex);
            totalTokens -= tokenizer.estimateTokenCountInMessage(removed);
        }

        if (hasSystemMessage && list.size() == 1 && totalTokens > maxTokensLimit) {
            list.clear();
        }

        return list;
    }
}
