package io.github.atengk.boot.redis.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务级分布式并发限流客户端单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisRateLimiterClient 单元测试")
class RedisRateLimiterClientTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RRateLimiter rateLimiter;

    private RedisRateLimiterClient rateLimiterClient;

    @BeforeEach
    void setUp() {
        rateLimiterClient = new RedisRateLimiterClient(redissonClient);
    }

    @Test
    @DisplayName("测试非阻塞 tryAcquire 令牌获取")
    void testTryAcquire() {
        when(redissonClient.getRateLimiter("api:order:create")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1L)).thenReturn(true);
        when(rateLimiter.tryAcquire(5L)).thenReturn(false);

        boolean acquired1 = rateLimiterClient.tryAcquire("api:order:create", 100L, Duration.ofSeconds(1));
        assertThat(acquired1).isTrue();
        verify(rateLimiter).trySetRate(RateType.OVERALL, 100L, Duration.ofSeconds(1));

        boolean acquired5 = rateLimiterClient.tryAcquire("api:order:create", 100L, Duration.ofSeconds(1), 5L);
        assertThat(acquired5).isFalse();
    }

    @Test
    @DisplayName("测试超时阻塞 tryAcquire 令牌获取")
    void testTryAcquireWithTimeout() {
        when(redissonClient.getRateLimiter("rpc:payment")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(2L, Duration.ofMillis(500))).thenReturn(true);

        boolean acquired = rateLimiterClient.tryAcquire("rpc:payment", 10L, Duration.ofSeconds(1), 2L, Duration.ofMillis(500));
        assertThat(acquired).isTrue();
        verify(rateLimiter).tryAcquire(2L, Duration.ofMillis(500));
    }

    @Test
    @DisplayName("测试阻塞 acquire 令牌获取")
    void testAcquire() {
        when(redissonClient.getRateLimiter("batch:job")).thenReturn(rateLimiter);

        rateLimiterClient.acquire("batch:job", 50L, Duration.ofSeconds(1));
        verify(rateLimiter).acquire(1L);

        rateLimiterClient.acquire("batch:job", 50L, Duration.ofSeconds(1), 10L);
        verify(rateLimiter).acquire(10L);
    }

    @Test
    @DisplayName("测试查看可用令牌与删除配置")
    void testAvailablePermitsAndDelete() {
        when(redissonClient.getRateLimiter("sms:send")).thenReturn(rateLimiter);
        when(rateLimiter.availablePermits()).thenReturn(8L);
        when(rateLimiter.delete()).thenReturn(true);

        assertThat(rateLimiterClient.getAvailablePermits("sms:send")).isEqualTo(8L);
        assertThat(rateLimiterClient.delete("sms:send")).isTrue();
    }

    @Test
    @DisplayName("测试入参防御性校验")
    void testValidation() {
        assertThatThrownBy(() -> rateLimiterClient.tryAcquire("", 10, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> rateLimiterClient.tryAcquire("key", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> rateLimiterClient.tryAcquire("key", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
