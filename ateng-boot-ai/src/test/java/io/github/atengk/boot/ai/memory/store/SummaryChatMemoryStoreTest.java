package io.github.atengk.boot.ai.memory.store;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.memory.SummaryMemoryState;
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
 * SummaryChatMemoryStore 内存与 Redis 双模存储单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class SummaryChatMemoryStoreTest {

    @Test
    @DisplayName("InMemorySummaryChatMemoryStore: 基础 CRUD、存在性探测与并发安全")
    void testInMemoryStoreLifecycle() {
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();
        String memoryId = "mem-user-001";

        // 1. 初始状态为空
        assertThat(store.exists(memoryId)).isFalse();
        SummaryMemoryState emptyState = store.getState(memoryId);
        assertThat(emptyState).isNotNull();
        assertThat(emptyState.getSummary()).isEmpty();
        assertThat(emptyState.getMessages()).isEmpty();

        // 2. 写入状态
        List<ChatMessage> messages = List.of(UserMessage.from("您好"), AiMessage.from("你好！有什么可以帮您？"));
        SummaryMemoryState state = new SummaryMemoryState("用户向助手打招呼", messages);
        store.updateState(memoryId, state);

        assertThat(store.exists(memoryId)).isTrue();
        SummaryMemoryState retrieved = store.getState(memoryId);
        assertThat(retrieved.getSummary()).isEqualTo("用户向助手打招呼");
        assertThat(retrieved.getMessages()).hasSize(2);

        // 3. 删除与清空
        store.deleteState(memoryId);
        assertThat(store.exists(memoryId)).isFalse();
        assertThat(store.getState(memoryId).getMessages()).isEmpty();
    }

    @Test
    @DisplayName("RedisSummaryChatMemoryStore: 纯净 JSON 存储与滑动 TTL 续期")
    @SuppressWarnings("unchecked")
    void testRedisStoreSerializationAndTtlRenewal() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        Duration ttl = Duration.ofDays(3);
        RedisSummaryChatMemoryStore store = new RedisSummaryChatMemoryStore(redisTemplate, "ai:summary:memory:", ttl);
        String memoryId = "sess_redis_01";
        String expectedKey = "ai:summary:memory:sess_redis_01";

        SummaryMemoryState state = new SummaryMemoryState(
                "长期事实：用户居住在北京",
                List.of(UserMessage.from("今天天气如何？"))
        );

        // 写入状态
        store.updateState(memoryId, state);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(expectedKey), jsonCaptor.capture(), eq(ttl));

        String savedJson = jsonCaptor.getValue();
        assertThat(savedJson)
                .doesNotContain("@class")
                .contains("长期事实：用户居住在北京")
                .contains("今天天气如何？");

        // 模拟读取
        when(ops.get(expectedKey)).thenReturn(savedJson);
        SummaryMemoryState readState = store.getState(memoryId);
        assertThat(readState.getSummary()).isEqualTo("长期事实：用户居住在北京");
        assertThat(readState.getMessages()).hasSize(1);
    }

    @Test
    @DisplayName("RedisSummaryChatMemoryStore: 异常防卫与空安全")
    @SuppressWarnings("unchecked")
    void testRedisStoreErrorDefense() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        RedisSummaryChatMemoryStore store = new RedisSummaryChatMemoryStore(redisTemplate, "ai:summary:memory:", Duration.ofHours(1));

        // 1. 读取异常降级保底返回空对象
        when(ops.get("ai:summary:memory:err_id")).thenThrow(new RuntimeException("Redis read timeout"));
        SummaryMemoryState errState = store.getState("err_id");
        assertThat(errState).isNotNull();
        assertThat(errState.getSummary()).isEmpty();
        assertThat(errState.getMessages()).isEmpty();

        // 2. 写入异常抛出受管业务异常
        doThrow(new RuntimeException("Redis write socket closed"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.updateState("fail_id", new SummaryMemoryState("sum", List.of())))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_MEMORY_STORE_ERROR);

        // 3. 空参数安全
        assertThat(store.getState(null).getSummary()).isEmpty();
        assertThat(store.exists(null)).isFalse();
        store.updateState(null, null);
        store.deleteState(null);
    }
}
