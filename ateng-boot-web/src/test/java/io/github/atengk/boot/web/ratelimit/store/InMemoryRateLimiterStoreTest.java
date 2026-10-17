package io.github.atengk.boot.web.ratelimit.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
}
