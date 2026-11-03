package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.memory.store.InMemoryChatMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryChatMemoryStore 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class InMemoryChatMemoryStoreTest {

    @Test
    @DisplayName("基础增删查与多会话隔离")
    void testBasicCrudAndIsolation() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();

        String sess1 = "user_1";
        String sess2 = "user_2";

        store.updateMessages(sess1, List.of(UserMessage.from("用户1消息")));
        store.updateMessages(sess2, List.of(UserMessage.from("用户2消息"), AiMessage.from("回复2")));

        assertThat(store.getMessageCount(sess1)).isEqualTo(1);
        assertThat(store.getMessageCount(sess2)).isEqualTo(2);

        assertThat(store.sessionCount()).isEqualTo(2);

        store.deleteMessages(sess1);
        assertThat(store.exists(sess1)).isFalse();
        assertThat(store.exists(sess2)).isTrue();

        store.clearAll();
        assertThat(store.sessionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("轮次截断与空安全")
    void testTruncateAndNullSafety() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        String sess = "sess_truncate";

        store.updateMessages(sess, List.of(
                UserMessage.from("1"),
                UserMessage.from("2"),
                UserMessage.from("3"),
                UserMessage.from("4"),
                UserMessage.from("5")
        ));

        store.truncateMessages(sess, 3);
        List<ChatMessage> messages = store.getMessages(sess);
        assertThat(messages).hasSize(3);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("3");

        store.truncateMessages(sess, 0);
        assertThat(store.exists(sess)).isFalse();

        // 空安全调用
        assertThat(store.getMessages(null)).isEmpty();
        assertThat(store.getMessageCount(null)).isEqualTo(0);
        assertThat(store.exists(null)).isFalse();
        store.updateMessages(null, null);
        store.deleteMessages(null);
    }
}
