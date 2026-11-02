package io.github.atengk.boot.job.snail.retry;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.snail.retry.context.InMemorySnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
