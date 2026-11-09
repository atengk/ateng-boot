package io.github.atengk.boot.web.ratelimit.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 内存令牌桶限流存储单测
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("纯 JDK 内存令牌桶限流存储单测")
class InMemoryRateLimiterStoreTest {

    @Test
    @DisplayName("令牌桶正常获取与超出频次拦截")
    void shouldAcquireAndBlockWhenExceeded() {
        InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
        try {
            String key = "test:user:1";
            // 允许 2 次 / 10 秒
            assertThat(store.tryAcquire(key, 2, 10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.tryAcquire(key, 2, 10, TimeUnit.SECONDS)).isTrue();
            // 第 3 次应被拦截
            assertThat(store.tryAcquire(key, 2, 10, TimeUnit.SECONDS)).isFalse();
        } finally {
            store.destroy();
        }
    }

    @Test
    @DisplayName("不同限流键之间相互独立隔离")
    void shouldIsolateDifferentKeys() {
        InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
        try {
            assertThat(store.tryAcquire("keyA", 1, 10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.tryAcquire("keyA", 1, 10, TimeUnit.SECONDS)).isFalse();

            assertThat(store.tryAcquire("keyB", 1, 10, TimeUnit.SECONDS)).isTrue();
        } finally {
            store.destroy();
        }
    }

    @Test
    @DisplayName("非法入参防御：count<=0 或 time<=0 直接放行")
    void shouldPassWhenParamNonPositive() {
        InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
        try {
            assertThat(store.tryAcquire("keyC", 0, 10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.tryAcquire("keyD", 10, 0, TimeUnit.SECONDS)).isTrue();
        } finally {
            store.destroy();
        }
    }

    @Test
    @DisplayName("测试 reset 重置配额与空白键防御")
    void shouldResetKeyAndFailFastOnBlankKey() {
        InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
        try {
            String key = "resetKey";
            assertThat(store.tryAcquire(key, 1, 60, TimeUnit.SECONDS)).isTrue();
            assertThat(store.tryAcquire(key, 1, 60, TimeUnit.SECONDS)).isFalse();

            // 重置后应恢复配额
            store.reset(key);
            assertThat(store.tryAcquire(key, 1, 60, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> store.reset(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.reset("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.tryAcquire("  ", 1, 10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            store.destroy();
        }
    }

    @Test
    @DisplayName("多线程并发获取令牌安全测试（验证 ReentrantLock 无锁状态泄露）")
    void shouldAcquireConcurrentlyWithoutLockLeak() throws InterruptedException {
        InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
        try {
            int threadCount = 20;
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

            // 令牌容量 5
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        if (store.tryAcquire("concurrentKey", 5, 60, TimeUnit.SECONDS)) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(5);
        } finally {
            store.destroy();
        }
    }

    @Test
    @DisplayName("测试 RateLimiterStore 默认接口方法的空白键防卫")
    void shouldFailFastOnDefaultInterfaceMethod() {
        RateLimiterStore defaultStore = new RateLimiterStore() {
            @Override
            public boolean tryAcquire(String key, long count, long time, TimeUnit unit) {
                return true;
            }
        };

        defaultStore.reset("validKey");
        assertThatThrownBy(() -> defaultStore.reset(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RateLimiter key must not be blank");
        assertThatThrownBy(() -> defaultStore.reset("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RateLimiter key must not be blank");
    }
}
