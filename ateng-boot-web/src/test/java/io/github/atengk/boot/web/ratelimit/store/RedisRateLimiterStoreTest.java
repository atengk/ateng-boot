package io.github.atengk.boot.web.ratelimit.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 基于 StringRedisTemplate 的分布式接口限流存储单元测试
 *
 * @author Ateng
 * @since 2026-11-09
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisRateLimiterStore 单元测试")
class RedisRateLimiterStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisRateLimiterStore store;

    @BeforeEach
    void setUp() {
        store = new RedisRateLimiterStore(redisTemplate);
    }

    @Test
    @DisplayName("执行 Lua 脚本成功获取令牌返回 true")
    void shouldReturnTrueWhenLuaScriptAcquires() {
        when(redisTemplate.execute(any(), eq(Collections.singletonList("rate:key:1")), anyString(), anyString(), anyString(), eq("1")))
                .thenReturn(1L);

        boolean acquired = store.tryAcquire("rate:key:1", 5, 10, TimeUnit.SECONDS);

        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("配额超限时 Lua 脚本返回 0L 结果为 false")
    void shouldReturnFalseWhenLuaScriptExceeds() {
        when(redisTemplate.execute(any(), eq(Collections.singletonList("rate:key:1")), anyString(), anyString(), anyString(), eq("1")))
                .thenReturn(0L);

        boolean acquired = store.tryAcquire("rate:key:1", 5, 10, TimeUnit.SECONDS);

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("测试 reset 重置配额与空白键校验")
    void shouldResetKeyAndFailFastOnBlankKey() {
        store.reset("rate:key:1");
        verify(redisTemplate, times(1)).delete("rate:key:1");

        assertThatThrownBy(() -> store.reset(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reset("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("底层 Redis 故障时转译为保留原始因果链的 BusinessException")
    void shouldWrapRedisException() {
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        assertThatThrownBy(() -> store.tryAcquire("rate:err", 1, 1, TimeUnit.SECONDS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis rate limiter tryAcquire failed")
                .hasCauseInstanceOf(RuntimeException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }
}
