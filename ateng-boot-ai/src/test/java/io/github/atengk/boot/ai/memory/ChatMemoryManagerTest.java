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

    @Test
    @DisplayName("启发式保底分词器 HeuristicTokenizer 纯原生离线估算测试")
    void testHeuristicTokenizer() {
        HeuristicTokenizer tokenizer = new HeuristicTokenizer();

        int textTokens = tokenizer.estimateTokenCountInText("Hello 世界 12345");
        assertThat(textTokens).isGreaterThan(0);

        UserMessage msg = UserMessage.from("测试单条消息");
        int msgTokens = tokenizer.estimateTokenCountInMessage(msg);
        assertThat(msgTokens).isGreaterThan(3);

        int msgsTokens = tokenizer.estimateTokenCountInMessages(List.of(
                SystemMessage.from("你是一个助手"),
                UserMessage.from("你好")
        ));
        assertThat(msgsTokens).isGreaterThan(msgTokens);
    }

    @Test
    @DisplayName("Token 数量统计与基于 Token 上限的截断 truncateByTokens 测试 (保留首条系统指令)")
    void testTruncateByTokens() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        HeuristicTokenizer tokenizer = new HeuristicTokenizer();
        io.github.atengk.boot.ai.properties.ChatMemoryProperties props = new io.github.atengk.boot.ai.properties.ChatMemoryProperties();
        props.setWindowType(io.github.atengk.boot.ai.properties.ChatMemoryWindowType.TOKEN);
        props.setMaxTokens(500);

        ChatMemoryManager manager = new ChatMemoryManager(store, props, tokenizer);
        String sessionId = "token_sess_001";

        SystemMessage sysMsg = SystemMessage.from("系统预设人设：专业金融助理，请务必严谨回答。");
        UserMessage u1 = UserMessage.from("请分析一下 2024 年第一季度的宏观经济走势以及科技板块的表现");
        AiMessage a1 = AiMessage.from("2024年第一季度宏观经济呈现温和复苏态势，科技板块中人工智能产业链表现亮眼...");
        UserMessage u2 = UserMessage.from("能否进一步说明半导体上下游产业链的库存周期变化情况？");
        AiMessage a2 = AiMessage.from("半导体板块目前处于周期筑底回升阶段，存储芯片价格回暖，代工产能利用率逐步爬坡...");

        manager.appendMessages(sessionId, List.of(sysMsg, u1, a1, u2, a2));

        int tokenCount = manager.getTokenCount(sessionId);
        assertThat(tokenCount).isGreaterThan(50);
        assertThat(manager.getMessageCount(sessionId)).isEqualTo(5);

        // 主动截断到较小的 Token 上限 (例如 60 tokens)
        manager.truncateByTokens(sessionId, 60);

        List<ChatMessage> remaining = manager.getMessages(sessionId);
        // 首条 SystemMessage 必须保留
        assertThat(remaining.get(0)).isInstanceOf(SystemMessage.class);
        // 消息总数应该减少了（早期 u1, a1 先进先出被淘汰）
        assertThat(remaining.size()).isLessThan(5);
        // 保留的 token 估算值应在限制内
        int remainingTokens = manager.getTokenCount(sessionId);
        assertThat(remainingTokens).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("TokenWindow 模式下追加消息自动触发 Token 上限裁剪防爆")
    void testTokenWindowAutomaticTruncationOnAppend() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        HeuristicTokenizer tokenizer = new HeuristicTokenizer();
        io.github.atengk.boot.ai.properties.ChatMemoryProperties props = new io.github.atengk.boot.ai.properties.ChatMemoryProperties();
        props.setWindowType(io.github.atengk.boot.ai.properties.ChatMemoryWindowType.TOKEN);
        props.setMaxTokens(70);

        ChatMemoryManager manager = new ChatMemoryManager(store, props, tokenizer);
        String sessionId = "token_auto_001";

        manager.appendMessage(sessionId, SystemMessage.from("核心指令"));
        manager.appendMessage(sessionId, UserMessage.from("长长长长长长长长长长长长长长长长长长长长长长长长长问题1"));
        manager.appendMessage(sessionId, AiMessage.from("长长长长长长长长长长长长长长长长长长长长长长长长长回答1"));
        manager.appendMessage(sessionId, UserMessage.from("长长长长长长长长长长长长长长长长长长长长长长长长长问题2"));
        manager.appendMessage(sessionId, AiMessage.from("长长长长长长长长长长长长长长长长长长长长长长长长长回答2"));

        // 验证自动裁剪后 Token 总量不超过 70，且系统消息仍保留
        assertThat(manager.getTokenCount(sessionId)).isLessThanOrEqualTo(70);
        List<ChatMessage> messages = manager.getMessages(sessionId);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
    }
}
