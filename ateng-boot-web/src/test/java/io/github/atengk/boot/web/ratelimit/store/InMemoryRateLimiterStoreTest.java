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

        assertThat(defaultStore.getAvailablePermits("validKey", 5, 10, TimeUnit.SECONDS)).isEqualTo(5L);
        assertThatThrownBy(() -> defaultStore.getAvailablePermits(" ", 5, 10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaultStore.getAvailablePermits("validKey", 0, 10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaultStore.getAvailablePermits("validKey", 5, 0, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaultStore.getAvailablePermits("validKey", 5, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getAvailablePermits 完整覆盖未初始化、递减、归零、只读不消费、reset 恢复、窗口恢复及参数防卫")
    void shouldQueryAvailablePermitsAccuratelyWithoutConsuming() throws InterruptedException {
        InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
        try {
            String key = "permits:order:1";
            // 1. 未初始化返回完整配额，且多次查询绝不扣减配额
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(2L);
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(2L);

            // 2. 消费 1 次后剩余 1，查询仍保持为 1
            assertThat(store.tryAcquire(key, 2, 10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(1L);

            // 3. 再次消费至超限，剩余配额归零
            assertThat(store.tryAcquire(key, 2, 10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(0L);
            assertThat(store.tryAcquire(key, 2, 10, TimeUnit.SECONDS)).isFalse();
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(0L);

            // 4. 手动 reset 后恢复完整配额
            store.reset(key);
            assertThat(store.getAvailablePermits(key, 2, 10, TimeUnit.SECONDS)).isEqualTo(2L);

            // 5. 短窗口过期后自动恢复完整配额
            String windowKey = "permits:window:1";
            assertThat(store.tryAcquire(windowKey, 1, 80, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(store.getAvailablePermits(windowKey, 1, 80, TimeUnit.MILLISECONDS)).isEqualTo(0L);
            Thread.sleep(110L);
            assertThat(store.getAvailablePermits(windowKey, 1, 80, TimeUnit.MILLISECONDS)).isEqualTo(1L);

            // 6. 非法参数快速失败抛出 IllegalArgumentException
            assertThatThrownBy(() -> store.getAvailablePermits(null, 2, 10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.getAvailablePermits("  ", 2, 10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.getAvailablePermits(key, 0, 10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.getAvailablePermits(key, 2, -1, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.getAvailablePermits(key, 2, 10, null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            store.destroy();
        }
    }
}
