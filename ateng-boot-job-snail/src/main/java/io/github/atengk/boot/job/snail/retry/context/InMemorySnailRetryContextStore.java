package io.github.atengk.boot.job.snail.retry.context;

import io.github.atengk.boot.core.context.AsyncContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于内存 LRU 缓存的默认 SnailJob 重试四维上下文快照存储实现
 *
 * <p>线程安全且带容量上限保护，默认保留至多 4096 条最近访问的快照记录，
 * 超过上限自动剔除最久未使用的陈旧快照，防御内存泄露与堆内存溢出。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class InMemorySnailRetryContextStore implements SnailRetryContextStore {

    public static final int DEFAULT_MAX_ENTRIES = 4096;

    private final Map<String, AsyncContext.ContextSnapshot> cache;

    public InMemorySnailRetryContextStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemorySnailRetryContextStore(int maxEntries) {
        int capacity = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(Math.min(capacity, 16), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AsyncContext.ContextSnapshot> eldest) {
                return size() > capacity;
            }
        });
    }

    @Override
    public void put(String key, AsyncContext.ContextSnapshot snapshot) {
        if (key != null && snapshot != null) {
            cache.put(key, snapshot);
        }
    }

    @Override
    public AsyncContext.ContextSnapshot get(String key) {
        return key != null ? cache.get(key) : null;
    }

    @Override
    public void remove(String key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
