package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.memory.store.RedisChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RedisChatMemoryStore 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class RedisChatMemoryStoreTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisChatMemoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        store = new RedisChatMemoryStore(stringRedisTemplate, "ai:chat:memory:", Duration.ofDays(7));
    }

    @Test
    @DisplayName("写入消息时使用纯净 JSON 序列化并挂载 TTL")
    void testUpdateMessagesWithPureJsonAndTtl() {
        String memoryId = "sess_100";
        List<ChatMessage> messages = List.of(
                UserMessage.from("用户问题"),
                AiMessage.from("AI回答")
        );

        store.updateMessages(memoryId, messages);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("ai:chat:memory:sess_100"), jsonCaptor.capture(), eq(Duration.ofDays(7)));

        String serializedJson = jsonCaptor.getValue();
        // 彻底杜绝 @class 污染
        assertThat(serializedJson)
                .doesNotContain("@class")
                .contains("用户问题")
                .contains("AI回答");
    }

    @Test
    @DisplayName("读取消息时正确反序列化，key 不存在时保底返回空列表")
    void testGetMessages() {
        String memoryId = "sess_200";
        String expectedKey = "ai:chat:memory:sess_200";

        // 1. 存在消息
        List<ChatMessage> source = List.of(UserMessage.from("历史消息"));
        String json = ChatMessageSerializer.messagesToJson(source);
        when(valueOperations.get(expectedKey)).thenReturn(json);

        List<ChatMessage> result = store.getMessages(memoryId);
        assertThat(result).hasSize(1);
        assertThat(((UserMessage) result.get(0)).singleText()).isEqualTo("历史消息");

        // 2. 不存在数据保底返回空集合
        when(valueOperations.get("ai:chat:memory:not_exist")).thenReturn(null);
        assertThat(store.getMessages("not_exist")).isEmpty();
    }

    @Test
    @DisplayName("对称操作：清空、探测与截断")
    void testDeleteAndTruncate() {
        String memoryId = "sess_300";
        String expectedKey = "ai:chat:memory:sess_300";

        // 1. 探测存在性
        when(stringRedisTemplate.hasKey(expectedKey)).thenReturn(true);
        assertThat(store.exists(memoryId)).isTrue();

        // 2. 删除
        store.deleteMessages(memoryId);
        verify(stringRedisTemplate).delete(expectedKey);

        // 3. 截断
        List<ChatMessage> source = List.of(
                UserMessage.from("1"),
                UserMessage.from("2"),
                UserMessage.from("3"),
                UserMessage.from("4")
        );
        when(valueOperations.get(expectedKey)).thenReturn(ChatMessageSerializer.messagesToJson(source));

        store.truncateMessages(memoryId, 2);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(expectedKey), captor.capture(), eq(Duration.ofDays(7)));
        assertThat(captor.getValue()).contains("3").contains("4").doesNotContain("1").doesNotContain("2");
    }

    @Test
    @DisplayName("Redis 异常降级防卫与空安全")
    void testResilienceAndNullSafety() {
        String expectedKey = "ai:chat:memory:sess_err";
        when(valueOperations.get(expectedKey)).thenThrow(new RuntimeException("Redis connection timed out"));

        // 读取异常保底返回空集合，不崩溃中断
        List<ChatMessage> messages = store.getMessages("sess_err");
        assertThat(messages).isEmpty();

        // 空安全参数调用
        assertThat(store.getMessages(null)).isEmpty();
        assertThat(store.exists(null)).isFalse();
        assertThat(store.getMessageCount(null)).isEqualTo(0);
        store.updateMessages(null, null);
        store.deleteMessages(null);
    }

    @Test
    @DisplayName("写入或删除 Redis 会话记忆异常时抛出受管业务异常，杜绝静默失败")
    void testUpdateAndDeleteFailureThrowsAiException() {
        doThrow(new RuntimeException("Redis cluster down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.updateMessages("sess_fail", List.of(UserMessage.from("hi"))))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_MEMORY_STORE_ERROR)
                .hasMessageContaining("向 Redis 写入智能体会话记忆失败");

        doThrow(new RuntimeException("Redis connection refused"))
                .when(stringRedisTemplate).delete("ai:chat:memory:sess_fail");

        assertThatThrownBy(() -> store.deleteMessages("sess_fail"))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_MEMORY_STORE_ERROR)
                .hasMessageContaining("从 Redis 删除智能体会话记忆失败");
    }
}
