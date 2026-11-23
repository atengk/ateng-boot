package io.github.atengk.boot.job.snail.retry;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.retry.context.InMemorySnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SnailRetryContextStore} 与 {@link InMemorySnailRetryContextStore} 单元测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
class SnailRetryContextStoreTest {

    @AfterEach
    void tearDown() {
        SnailRetryContextRegistry.clear();
        SnailRetryContextRegistry.setStore(new InMemorySnailRetryContextStore());
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("InMemorySnailRetryContextStore: 基础 put、get、remove 与 clear 操作")
    void testBasicStoreOperations() {
        SnailRetryContextStore store = new InMemorySnailRetryContextStore(100);
        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();

        store.put("key-1", snapshot);
        assertThat(store.get("key-1")).isEqualTo(snapshot);

        store.remove("key-1");
        assertThat(store.get("key-1")).isNull();

        store.put("key-2", snapshot);
        store.clear();
        assertThat(store.get("key-2")).isNull();
    }

    @Test
    @DisplayName("InMemorySnailRetryContextStore: 边界与防御性处理")
    void testEdgeCases() {
        SnailRetryContextStore store = new InMemorySnailRetryContextStore(-5); // 触发兜底容量
        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();

        // null key 或 null value 不应抛出异常
        store.put(null, snapshot);
        store.put("k", null);
        assertThat(store.get(null)).isNull();
        store.remove(null);
    }

    @Test
    @DisplayName("InMemorySnailRetryContextStore: LRU 上限驱逐验证")
    void testLruEviction() {
        int capacity = 5;
        SnailRetryContextStore store = new InMemorySnailRetryContextStore(capacity);

        for (int i = 0; i < 10; i++) {
            TenantContextHolder.setTenantId("tenant-" + i);
            store.put("key-" + i, AsyncContext.ContextSnapshot.capture());
        }

        // 最早的 key 应已被 LRU 驱逐
        assertThat(store.get("key-0")).isNull();
        assertThat(store.get("key-1")).isNull();
        assertThat(store.get("key-2")).isNull();
        assertThat(store.get("key-3")).isNull();
        assertThat(store.get("key-4")).isNull();

        // 最近写入的 key 仍在
        assertThat(store.get("key-5")).isNotNull();
        assertThat(store.get("key-9")).isNotNull();
    }

    @Test
    @DisplayName("SnailRetryContextRegistry: 静态门面委托至指定 Store")
    void testRegistryDelegation() {
        CustomStore customStore = new CustomStore();
        SnailRetryContextRegistry.setStore(customStore);

        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();
        SnailRetryContextRegistry.put("custom-key", snapshot);

        assertThat(customStore.storage.get("custom-key")).isEqualTo(snapshot);
        assertThat(SnailRetryContextRegistry.get("custom-key")).isEqualTo(snapshot);

        SnailRetryContextRegistry.remove("custom-key");
        assertThat(customStore.storage.containsKey("custom-key")).isFalse();
    }

    @Test
    @DisplayName("InMemorySnailRetryContextStore: 基于 Instant 的 TTL 惰性过期清理且未过期条目保留")
    void testTtlLazyExpiration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        InMemorySnailRetryContextStore store = new InMemorySnailRetryContextStore(16, Duration.ofMinutes(10), clock);

        TenantContextHolder.setTenantId("tenant-ttl");
        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();
        store.put("ttl-key", snapshot);

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(store.get("ttl-key")).isSameAs(snapshot);

        clock.advance(Duration.ofSeconds(1));
        assertThat(store.get("ttl-key")).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("InMemorySnailRetryContextStore: 零 synchronized 方法且高并发读写无竞态失真")
    void testConcurrentAccessAndNoSynchronizedModifiers() throws Exception {
        for (Method m : InMemorySnailRetryContextStore.class.getDeclaredMethods()) {
            assertThat(Modifier.isSynchronized(m.getModifiers()))
                    .as("方法 %s 不得使用 synchronized", m.getName())
                    .isFalse();
        }

        InMemorySnailRetryContextStore store = new InMemorySnailRetryContextStore(500, Duration.ofHours(1));
        int threads = 8;
        int opsPerThread = 100;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setThreadNamePrefix("retry-ctx-test-");
        pool.initialize();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                Callable<Void> task = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "k-" + threadIdx + "-" + i;
                        TenantContextHolder.setTenantId("tenant-" + threadIdx);
                        try {
                            store.put(key, AsyncContext.ContextSnapshot.capture());
                            assertThat(store.get(key)).isNotNull();
                        } finally {
                            TenantContextHolder.clear();
                        }
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(store.size()).isLessThanOrEqualTo(500);
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            current.updateAndGet(t -> t.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    static class CustomStore implements SnailRetryContextStore {
        final Map<String, AsyncContext.ContextSnapshot> storage = new ConcurrentHashMap<>();

        @Override
        public void put(String key, AsyncContext.ContextSnapshot snapshot) {
            storage.put(key, snapshot);
        }

        @Override
        public AsyncContext.ContextSnapshot get(String key) {
            return storage.get(key);
        }

        @Override
        public void remove(String key) {
            storage.remove(key);
        }

        @Override
        public void clear() {
            storage.clear();
        }
    }
}
