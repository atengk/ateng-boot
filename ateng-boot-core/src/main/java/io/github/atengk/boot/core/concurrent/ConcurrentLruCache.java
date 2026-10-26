package io.github.atengk.boot.core.concurrent;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 纯原生有界 LRU 缓存容器
 * 100% 依托 JDK 17 原生能力，基于 access-order LinkedHashMap 与 ReentrantLock 构建
 * 支持容量上限自动淘汰、可选 Duration TTL 惰性过期与原子的 computeIfAbsent 计算，彻底杜绝无界内存膨胀与 OOM 隐患
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Ateng
 * @since 2026-10-27
 */
public class ConcurrentLruCache<K, V> {

    private final int maxCapacity;
    private final Duration ttl;
    private final Map<K, CacheEntry<V>> map;
    private final ReentrantLock lock;

    /**
     * 构造指定容量上限的 LRU 缓存（条目永不过期，仅由容量上限触发淘汰）
     *
     * @param maxCapacity 最大缓存条目容量（必须大于 0）
     */
    public ConcurrentLruCache(int maxCapacity) {
        this(maxCapacity, null);
    }

    /**
     * 构造指定容量上限与可选存活时间（TTL）的 LRU 缓存
     *
     * @param maxCapacity 最大缓存条目容量（必须大于 0）
     * @param ttl         条目默认存活时间（为 null 或 isZero 时表示不过期）
     */
    public ConcurrentLruCache(int maxCapacity, Duration ttl) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity 必须大于 0");
        }
        if (ttl != null && ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 不能为负数");
        }

        this.maxCapacity = maxCapacity;
        this.ttl = (ttl == null || ttl.isZero()) ? null : ttl;
        this.lock = new ReentrantLock();
        this.map = new LinkedHashMap<>(Math.min(maxCapacity, 16), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                return size() > maxCapacity;
            }
        };
    }

    /**
     * 根据键获取缓存值（自动刷新访问顺序；若已过期则惰性清理并返回 null）
     *
     * @param key 缓存键（非空）
     * @return 缓存值；若不存在或已过期则返回 null
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key 不能为空");
        lock.lock();
        try {
            CacheEntry<V> entry = map.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired(System.currentTimeMillis())) {
                map.remove(key);
                return null;
            }
            return entry.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写入或更新缓存条目
     *
     * @param key   缓存键（非空）
     * @param value 缓存值（非空）
     * @return 该键原本关联的历史有效值；若之前无值或旧值已过期则返回 null
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key 不能为空");
        Objects.requireNonNull(value, "value 不能为空");
        long now = System.currentTimeMillis();
        long expireTime = calculateExpireTime(now);

        lock.lock();
        try {
            CacheEntry<V> old = map.put(key, new CacheEntry<>(value, expireTime));
            if (old == null || old.isExpired(now)) {
                return null;
            }
            return old.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当键不存在或已过期时，原子执行映射计算函数并写入缓存
     *
     * @param key             缓存键（非空）
     * @param mappingFunction 计算函数（非空）
     * @return 当前有效值或新计算出的值；若计算函数返回 null 则不写入且返回 null
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(key, "key 不能为空");
        Objects.requireNonNull(mappingFunction, "mappingFunction 不能为空");

        lock.lock();
        try {
            long now = System.currentTimeMillis();
            CacheEntry<V> entry = map.get(key);
            if (entry != null) {
                if (!entry.isExpired(now)) {
                    return entry.value;
                }
                map.remove(key);
            }

            V computed = mappingFunction.apply(key);
            if (computed != null) {
                map.put(key, new CacheEntry<>(computed, calculateExpireTime(now)));
            }
            return computed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除指定键的缓存条目
     *
     * @param key 缓存键（非空）
     * @return 被移除的有效值；若之前不存在或已过期则返回 null
     */
    public V remove(K key) {
        Objects.requireNonNull(key, "key 不能为空");
        lock.lock();
        try {
            CacheEntry<V> removed = map.remove(key);
            if (removed == null || removed.isExpired(System.currentTimeMillis())) {
                return null;
            }
            return removed.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空当前所有缓存条目
     */
    public void clear() {
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前有效缓存条目数量
     *
     * @return 当前条目数
     */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断缓存是否为空
     *
     * @return 为空返回 true，否则返回 false
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 判断指定键是否存在且未过期
     *
     * @param key 缓存键（非空）
     * @return 存在且有效返回 true，否则返回 false
     */
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key 不能为空");
        lock.lock();
        try {
            CacheEntry<V> entry = map.get(key);
            if (entry == null) {
                return false;
            }
            if (entry.isExpired(System.currentTimeMillis())) {
                map.remove(key);
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 主动全量扫描并清理已过期的条目
     *
     * @return 本次清理的过期条目数
     */
    public int cleanUp() {
        if (ttl == null) {
            return 0;
        }
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            int removed = 0;
            Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().isExpired(now)) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取最大容量上限
     *
     * @return 最大容量
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * 获取配置的存活时间
     *
     * @return 存活时间 Duration；无 TTL 时返回 null
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * 计算过期时间戳（毫秒）
     *
     * @param now 当前时间戳
     * @return 过期时间戳；无 TTL 时返回 -1
     */
    private long calculateExpireTime(long now) {
        return ttl != null ? now + ttl.toMillis() : -1L;
    }

    /**
     * 内部缓存条目包装对象
     *
     * @param <V> 值类型
     */
    private static final class CacheEntry<V> {
        private final V value;
        private final long expireTimeMillis;

        CacheEntry(V value, long expireTimeMillis) {
            this.value = value;
            this.expireTimeMillis = expireTimeMillis;
        }

        boolean isExpired(long now) {
            return expireTimeMillis > 0 && now >= expireTimeMillis;
        }
    }
}
