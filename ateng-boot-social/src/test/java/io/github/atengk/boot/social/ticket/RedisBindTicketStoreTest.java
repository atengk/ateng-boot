package io.github.atengk.boot.social.ticket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.social.model.SocialUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisBindTicketStore 分布式票据仓库单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class RedisBindTicketStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisBindTicketStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisBindTicketStore(redisTemplate);
    }

    @Test
    @DisplayName("验证保存 SocialUser 至 Redis")
    void testSave() {
        SocialUser user = SocialUser.builder()
                .openId("open_redis")
                .unionId("union_redis")
                .source("GITEE")
                .nickname("Gitee开发者")
                .build();

        store.save("ticket_123", user, Duration.ofMinutes(10));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("social:bind_ticket:ticket_123"), jsonCaptor.capture(), eq(Duration.ofMinutes(10)));

        SocialUser deserialized = JsonUtil.parseObject(jsonCaptor.getValue(), SocialUser.class);
        assertThat(deserialized.getOpenId()).isEqualTo("open_redis");
        assertThat(deserialized.getNickname()).isEqualTo("Gitee开发者");
    }

    @Test
    @DisplayName("验证 remove 原子调用 Lua 脚本获取并销毁")
    @SuppressWarnings("unchecked")
    void testRemove() {
        SocialUser user = SocialUser.builder().openId("open_atomic").build();
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("social:bind_ticket:ticket_atomic"))))
                .thenReturn(JsonUtil.toJsonString(user));

        SocialUser removed = store.remove("ticket_atomic");
        assertThat(removed).isNotNull();
        assertThat(removed.getOpenId()).isEqualTo("open_atomic");

        // 重复调用返回 null
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("social:bind_ticket:ticket_atomic"))))
                .thenReturn(null);
        assertThat(store.remove("ticket_atomic")).isNull();
    }

    @Test
    @DisplayName("验证 get 与 contains 边界")
    void testGetAndContains() {
        SocialUser user = SocialUser.builder().openId("o_get").build();
        when(valueOperations.get("social:bind_ticket:ticket_get")).thenReturn(JsonUtil.toJsonString(user));
        assertThat(store.get("ticket_get")).isNotNull();

        when(redisTemplate.hasKey("social:bind_ticket:ticket_get")).thenReturn(Boolean.TRUE);
        assertThat(store.contains("ticket_get")).isTrue();

        assertThat(store.contains(null)).isFalse();
        assertThat(store.get(null)).isNull();
    }
}
