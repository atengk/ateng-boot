package io.github.atengk.boot.web.ratelimit.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 基于 RedissonClient 的分布式接口限流存储单元测试
 *
 * @author Ateng
 * @since 2026-11-09
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedissonRateLimiterStore 单元测试")
class RedissonRateLimiterStoreTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RRateLimiter rateLimiter;

    private RedissonRateLimiterStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        store = new RedissonRateLimiterStore(redissonClient);
    }

    @Test
    @DisplayName("Redisson 成功获取令牌返回 true")
    void shouldReturnTrueWhenRedissonAcquires() {
        when(rateLimiter.tryAcquire(1)).thenReturn(true);

        boolean acquired = store.tryAcquire("rate:redisson:1", 5, 10, TimeUnit.SECONDS);

        assertThat(acquired).isTrue();
        verify(rateLimiter, times(1)).trySetRate(eq(RateType.OVERALL), eq(5L), eq(Duration.ofSeconds(10)));
        verify(rateLimiter, times(1)).tryAcquire(1);
    }

    @Test
    @DisplayName("Redisson 超限时返回 false")
    void shouldReturnFalseWhenRedissonExceeds() {
        when(rateLimiter.tryAcquire(1)).thenReturn(false);

        boolean acquired = store.tryAcquire("rate:redisson:1", 5, 10, TimeUnit.SECONDS);

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("测试 reset 重置配额与删除 RRateLimiter")
    void shouldResetKey() {
        store.reset("rate:redisson:1");
        verify(rateLimiter, times(1)).delete();

        assertThatThrownBy(() -> store.reset(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("底层 Redisson 故障时转译为保留原始因果链的 BusinessException")
    void shouldWrapRedissonException() {
        when(rateLimiter.tryAcquire(1)).thenThrow(new RuntimeException("Redisson timeout"));

        assertThatThrownBy(() -> store.tryAcquire("rate:redisson:err", 1, 1, TimeUnit.SECONDS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redisson rate limiter tryAcquire failed")
                .hasCauseInstanceOf(RuntimeException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }

    @Test
    @DisplayName("getAvailablePermits 未初始化返回满配额、存在时查询 availablePermits 且绝不触发 trySetRate/tryAcquire")
    void shouldQueryAvailablePermitsSafely() {
        when(rateLimiter.isExists()).thenReturn(false).thenReturn(true).thenReturn(true);
        when(rateLimiter.availablePermits()).thenReturn(3L).thenReturn(0L);

        // 未初始化 / 已过期 / reset 后：直接返回 count，不初始化也不扣减
        assertThat(store.getAvailablePermits("rate:redisson:probe", 5, 10, TimeUnit.SECONDS)).isEqualTo(5L);
        verify(rateLimiter, never()).availablePermits();

        // 已初始化：返回剩余配额并绝不写回配额
        assertThat(store.getAvailablePermits("rate:redisson:probe", 5, 10, TimeUnit.SECONDS)).isEqualTo(3L);
        assertThat(store.getAvailablePermits("rate:redisson:probe", 5, 10, TimeUnit.SECONDS)).isEqualTo(0L);
        verify(rateLimiter, never()).trySetRate(any(), anyLong(), any());
        verify(rateLimiter, never()).tryAcquire(anyLong());

        // 非法参数快速失败
        assertThatThrownBy(() -> store.getAvailablePermits("  ", 5, 10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.getAvailablePermits("rate:redisson:probe", 0, 10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.getAvailablePermits("rate:redisson:probe", 5, 0, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.getAvailablePermits("rate:redisson:probe", 5, 10, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 底层故障保留原始因果链
        when(rateLimiter.isExists()).thenThrow(new RuntimeException("Redisson connection lost"));
        assertThatThrownBy(() -> store.getAvailablePermits("rate:redisson:probe", 5, 10, TimeUnit.SECONDS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redisson rate limiter getAvailablePermits failed")
                .hasCauseInstanceOf(RuntimeException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }
}
