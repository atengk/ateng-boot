package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.memory.store.InMemoryChatMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 会话记忆对称管理门面 ChatMemoryManager 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class ChatMemoryManagerTest {

    @Test
    @DisplayName("完备对称操作：读取、全量写入、追加、清空、探测与截断")
    void testSymmetricalOperations() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        ChatMemoryManager manager = new ChatMemoryManager(store, 5);

        String sessionId = "sess_001";

        // 1. 初始状态探测
        assertThat(manager.exists(sessionId)).isFalse();
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(0);
        assertThat(manager.getMessages(sessionId)).isEmpty();

        // 2. 单条与批量追加
        manager.appendMessage(sessionId, SystemMessage.from("你是一个助手"));
        manager.appendMessage(sessionId, UserMessage.from("你好"));
        manager.appendMessage(sessionId, AiMessage.from("你好！有什么我可以帮你的？"));

        assertThat(manager.exists(sessionId)).isTrue();
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(3);
        List<ChatMessage> messages = manager.getMessages(sessionId);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);

        // 3. 追加超过最大滑动窗口容量（5条）触发自动截断
        manager.appendMessages(sessionId, List.of(
                UserMessage.from("问题1"),
                AiMessage.from("回答1"),
                UserMessage.from("问题2"),
                AiMessage.from("回答2")
        ));
        // 总计尝试 7 条，maxMessages 限制为 5
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(5);
        List<ChatMessage> truncated = manager.getMessages(sessionId);
        assertThat(((AiMessage) truncated.get(truncated.size() - 1)).text()).isEqualTo("回答2");

        // 4. 主动显式截断为 2 条
        manager.truncate(sessionId, 2);
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(2);

        // 5. 全量更新消息列表
        manager.updateMessages(sessionId, List.of(UserMessage.from("全新起点")));
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(1);

        // 6. 对称清空
        manager.clear(sessionId);
        assertThat(manager.exists(sessionId)).isFalse();
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(0);
        assertThat(manager.getMessages(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("空安全防御性契约")
    void testNullSafety() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        ChatMemoryManager manager = new ChatMemoryManager(store, 20);

        assertThat(manager.getMessages(null)).isEmpty();
        assertThat(manager.getMessageCount(null)).isEqualTo(0);
        assertThat(manager.exists(null)).isFalse();

        // 不应抛出异常
        manager.clear(null);
        manager.truncate(null, 5);
        manager.truncate(null);

        // 必须参数校验
        assertThatThrownBy(() -> manager.updateMessages(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.appendMessage(null, UserMessage.from("hi")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.appendMessage("sess", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
