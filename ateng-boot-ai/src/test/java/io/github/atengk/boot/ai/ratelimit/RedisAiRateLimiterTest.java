package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 分布式滑动窗口限流器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class RedisAiRateLimiterTest {

    @Test
    @DisplayName("Lua 脚本返回 1 时允许通过，返回 0 或 -1 时触发限流")
    void testRedisLuaTryAcquireBehavior() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultRps(5);
        props.setDefaultTpm(10000);

        RedisAiRateLimiter limiter = new RedisAiRateLimiter(props, redisTemplate);
        assertThat(limiter.isDistributed()).isTrue();

        // 模拟 Lua 返回 1 (成功)
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        assertThat(limiter.tryAcquire("t1", "gpt-4o", 100)).isTrue();

        // 模拟 Lua 返回 0 (RPS 限流)
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        assertThat(limiter.tryAcquire("t1", "gpt-4o", 100)).isFalse();

        // 验证 acquire() 抛出 AI_RATE_LIMIT_EXCEEDED
        assertThatThrownBy(() -> limiter.acquire("t1", "gpt-4o", 100))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> assertThat(((AiException) ex).getCode()).isEqualTo(AiResultCode.AI_RATE_LIMIT_EXCEEDED.getCode()));

        // 模拟 Lua 返回 -1 (TPM 限流)
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-1L);
        assertThat(limiter.tryAcquire("t1", "gpt-4o", 100)).isFalse();
    }

    @Test
    @DisplayName("Redis 异常时平滑降级放行，防御业务阻断 (Fail-Open)")
    void testRedisFailOpenOnConnectionError() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultRps(10);

        RedisAiRateLimiter limiter = new RedisAiRateLimiter(props, redisTemplate);

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        // Redis 崩溃时自动降级平滑放行，避免造成业务雪崩
        assertThat(limiter.tryAcquire("t1", "gpt-4o", 50)).isTrue();
    }

    @Test
    @DisplayName("浮点数 RPS（如 0.5）传递给 Lua 时不应被强转截断为 0")
    void testFloatRpsNotTruncated() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultRps(0.5); // 0.5 req/sec (每 2 秒 1 次)
        props.setDefaultTpm(1000);

        RedisAiRateLimiter limiter = new RedisAiRateLimiter(props, redisTemplate);

        org.mockito.ArgumentCaptor<String> rpsCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                rpsCaptor.capture(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        limiter.tryAcquire("t1", "gpt-4o", 100);

        assertThat(rpsCaptor.getValue()).isEqualTo("0.5");
    }
}
