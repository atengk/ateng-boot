package io.github.atengk.boot.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TokenBucketLimiter} 纯无锁平滑令牌桶限流器单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("TokenBucketLimiter 纯无锁平滑令牌桶限流器测试")
class TokenBucketLimiterTest {

    @Test
    @DisplayName("基础容量与非阻塞获取：在容量范围内能成功获取，超出容量立即失败")
    void shouldAcquireTokensWithinCapacity() {
        // 容量 5，每秒填充 1 个令牌
        TokenBucketLimiter limiter = new TokenBucketLimiter(5, 1.0);

        assertEquals(5, limiter.getCapacity());
        assertEquals(1.0, limiter.getRefillTokensPerSecond());

        // 连续获取 5 个令牌，全部成功
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }

        // 此时令牌耗尽，再次获取立即返回 false
        assertFalse(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    @DisplayName("令牌惰性填充：等待一段时间后，令牌按速率恢复")
    void shouldRefillTokensOverTime() throws InterruptedException {
        // 容量 10，每秒填充 100 个令牌（即每 10ms 填充 1 个）
        TokenBucketLimiter limiter = new TokenBucketLimiter(10, 100.0);

        // 先耗尽所有令牌
        assertTrue(limiter.tryAcquire(10));
        assertFalse(limiter.tryAcquire(1));

        // 等待 50ms，应该补充约 5 个令牌
        Thread.sleep(50);

        assertTrue(limiter.tryAcquire(3));
    }

    @Test
    @DisplayName("带超时等待获取：在超时期限内成功等待到新令牌产生")
    void shouldAcquireWithTimeoutSuccessfully() {
        // 每秒 50 个令牌（每 20ms 生成 1 个），初始满桶 2 个
        TokenBucketLimiter limiter = new TokenBucketLimiter(2, 50.0);

        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(1)); // 立即获取失败

        // 等待最多 100ms 获取 1 个令牌（只需等待约 20ms）
        assertTrue(limiter.tryAcquire(1, Duration.ofMillis(100)));

        // 等待时间不足（超时阈值过短），应该快速失败
        assertFalse(limiter.tryAcquire(10, Duration.ofMillis(10)));
    }

    @Test
    @DisplayName("并发竞争对抗测试：多线程高并发争抢令牌，总量绝对不超过容量加填充量")
    void shouldMaintainThreadSafetyUnderHighConcurrency() throws InterruptedException {
        int threads = 10;
        int capacity = 20;
        // 每秒补充 10 个令牌
        TokenBucketLimiter limiter = new TokenBucketLimiter(capacity, 10.0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicInteger totalAcquired = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    // 每个线程尝试获取 5 次
                    for (int j = 0; j < 5; j++) {
                        if (limiter.tryAcquire()) {
                            totalAcquired.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 统一发令起跑
        startLatch.countDown();
        assertTrue(endLatch.await(3, TimeUnit.SECONDS));
        pool.shutdown();

        // 初始容量为 20，极短并发时间内最多获取 20~21 个，绝不会超发
        assertTrue(totalAcquired.get() <= capacity + 2, "获取总数超出了容量限制: " + totalAcquired.get());
        assertTrue(totalAcquired.get() >= 15, "高并发争抢成功数过低: " + totalAcquired.get());
    }

    @Test
    @DisplayName("边界与非法入参防卫测试")
    void shouldThrowOnInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(-1, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(10, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(10, -5.0));

        TokenBucketLimiter limiter = new TokenBucketLimiter(10, 10.0);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(1, (Duration) null));
    }
}
