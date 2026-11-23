package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SummaryMemoryState 状态模型与纯净序列化单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class SummaryMemoryStateTest {

    @Test
    @DisplayName("验证纯净 JSON 序列化：无 @class 污染且包含 summary 与 messages 结构")
    void testPureJsonSerialization() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个助手"),
                UserMessage.from("用户偏好：喜欢简洁回答"),
                AiMessage.from("收到，将保持简洁。")
        );
        String summary = "用户偏好喜欢简洁回答，智能体已确认。";
        SummaryMemoryState state = new SummaryMemoryState(summary, messages);

        String json = state.toJson();

        // 彻底杜绝 @class 多态元数据污染
        assertThat(json)
                .doesNotContain("@class")
                .contains("\"summary\"")
                .contains("\"messages\"")
                .contains("用户偏好：喜欢简洁回答")
                .contains("用户偏好喜欢简洁回答，智能体已确认。");
    }

    @Test
    @DisplayName("验证反序列化：完整恢复各类型 ChatMessage 与 summary 字段")
    void testDeserialization() {
        List<ChatMessage> originalMessages = List.of(
                UserMessage.from("第一轮提问"),
                AiMessage.from("第一轮回答")
        );
        String originalSummary = "历史摘要内容";
        SummaryMemoryState original = new SummaryMemoryState(originalSummary, originalMessages);

        String json = original.toJson();
        SummaryMemoryState restored = SummaryMemoryState.fromJson(json);

        assertThat(restored.getSummary()).isEqualTo(originalSummary);
        assertThat(restored.getMessages()).hasSize(2);
        assertThat(restored.getMessages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) restored.getMessages().get(0)).singleText()).isEqualTo("第一轮提问");
        assertThat(restored.getMessages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) restored.getMessages().get(1)).text()).isEqualTo("第一轮回答");
    }

    @Test
    @DisplayName("空安全与容错：null 或空 JSON 反序列化保底返回空状态，绝不崩溃或返回 null")
    void testNullSafetyAndDefensiveDefaults() {
        // null 反序列化
        SummaryMemoryState fromNull = SummaryMemoryState.fromJson(null);
        assertThat(fromNull).isNotNull();
        assertThat(fromNull.getSummary()).isEmpty();
        assertThat(fromNull.getMessages()).isEmpty();

        // 空串反序列化
        SummaryMemoryState fromBlank = SummaryMemoryState.fromJson("   ");
        assertThat(fromBlank).isNotNull();
        assertThat(fromBlank.getSummary()).isEmpty();
        assertThat(fromBlank.getMessages()).isEmpty();

        // 畸形 JSON 容错
        SummaryMemoryState fromCorrupt = SummaryMemoryState.fromJson("{corrupt_json}");
        assertThat(fromCorrupt).isNotNull();
        assertThat(fromCorrupt.getSummary()).isEmpty();
        assertThat(fromCorrupt.getMessages()).isEmpty();

        // 构造方法传入 null 参数防御
        SummaryMemoryState stateWithNulls = new SummaryMemoryState(null, null);
        assertThat(stateWithNulls.getSummary()).isEmpty();
        assertThat(stateWithNulls.getMessages()).isEmpty();
    }
}
