package io.github.atengk.boot.sms.support;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 基于 ReentrantLock 的有界 LRU 内存存储容器
 * 杜绝 JDK 21+ 虚拟线程 Carrier Pinning，并在容量触顶时优先惰性清理过期条目再执行 LRU 淘汰
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Ateng
 * @since 2026-11-21
 */
public final class BoundedLruMap<K, V> {

    /**
     * 默认最大条目容量
     */
    public static final int DEFAULT_MAX_ENTRIES = 50_000;

    /**
     * 单次满容写入时全量探测过期的地图规模阈值，超过后按有界窗口采样清理防 O(N) 抖动
     */
    private static final int FULL_SWEEP_SIZE_THRESHOLD = 512;
    private static final int BOUNDED_SWEEP_LIMIT = 256;

    private final int maxEntries;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, V> storage = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedLruMap(int maxEntries) {
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    }

    /**
     * 在互斥锁保护下执行受控读写与过期容量治理操作
     *
     * @param action 受锁保护执行回调
     * @param <R>    返回值类型
     * @return 回调执行结果
     */
    public <R> R withLock(Function<StorageView<K, V>, R> action) {
        lock.lock();
        try {
            return action.apply(new StorageView<>() {
                @Override
                public V get(K key) {
                    return storage.get(key);
                }

                @Override
                public void put(K key, V value) {
                    putWithEvict(key, value, null);
                }

                @Override
                public void put(K key, V value, Predicate<V> expiredPredicate) {
                    putWithEvict(key, value, expiredPredicate);
                }

                @Override
                public V remove(K key) {
                    return storage.remove(key);
                }

                @Override
                public int size() {
                    return storage.size();
                }

                @Override
                public void clear() {
                    storage.clear();
                }
            });
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 线程安全读取指定键的值
     *
     * @param key 键
     * @return 对应值，不存在时返回 null
     */
    public V get(K key) {
        return withLock(view -> view.get(key));
    }

    /**
     * 线程安全写入键值对并在达到容量上限时淘汰过期或最久未访问条目
     *
     * @param key              键
     * @param value            值
     * @param expiredPredicate 过期判定谓词（可为 null）
     */
    public void put(K key, V value, Predicate<V> expiredPredicate) {
        withLock(view -> {
            view.put(key, value, expiredPredicate);
            return null;
        });
    }

    /**
     * 移除指定键
     *
     * @param key 键
     * @return 被移除的旧值
     */
    public V remove(K key) {
        return withLock(view -> view.remove(key));
    }

    /**
     * 清空所有条目
     */
    public void clear() {
        withLock(view -> {
            view.clear();
            return null;
        });
    }

    /**
     * 获取当前已存储条目数
     *
     * @return 条目数
     */
    public int size() {
        return withLock(StorageView::size);
    }

    /**
     * 获取配置的最大条目上限
     *
     * @return 容量上限
     */
    public int getMaxEntries() {
        return maxEntries;
    }

    private void putWithEvict(K key, V value, Predicate<V> expiredPredicate) {
        evictIfNeeded(key, expiredPredicate);
        storage.put(key, value);
    }

    private void evictIfNeeded(K incomingKey, Predicate<V> expiredPredicate) {
        if (storage.containsKey(incomingKey) || storage.size() < maxEntries) {
            return;
        }
        if (expiredPredicate != null) {
            int maxChecks = storage.size() <= FULL_SWEEP_SIZE_THRESHOLD ? storage.size() : BOUNDED_SWEEP_LIMIT;
            int checked = 0;
            Iterator<Map.Entry<K, V>> iterator = storage.entrySet().iterator();
            while (iterator.hasNext() && checked < maxChecks) {
                Map.Entry<K, V> entry = iterator.next();
                checked++;
                if (expiredPredicate.test(entry.getValue())) {
                    iterator.remove();
                }
            }
        }
        Iterator<Map.Entry<K, V>> eldestIterator = storage.entrySet().iterator();
        while (storage.size() >= maxEntries && eldestIterator.hasNext()) {
            eldestIterator.next();
            eldestIterator.remove();
        }
    }

    /**
     * 锁内受控存储操作视图（仅在持锁回调生命周期内安全使用）
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    public interface StorageView<K, V> {

        V get(K key);

        void put(K key, V value);

        void put(K key, V value, Predicate<V> expiredPredicate);

        V remove(K key);

        int size();

        void clear();
    }
}
