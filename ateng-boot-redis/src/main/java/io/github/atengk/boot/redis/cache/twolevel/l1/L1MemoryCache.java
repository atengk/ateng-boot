package io.github.atengk.boot.redis.cache.twolevel.l1;

/**
 * L1 本地内存缓存基础抽象契约
 * 统一屏蔽 Caffeine 与 JVM LRU 原生内存数据结构的实现差异
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface L1MemoryCache {

    /**
     * 获取本地缓存对象
     *
     * @param key 缓存键
     * @return 缓存值，不存在或已过期返回 null
     */
    Object get(Object key);

    /**
     * 写入本地缓存
     *
     * @param key 缓存键
     * @param value 缓存值
     */
    void put(Object key, Object value);

    /**
     * 淘汰指定的本地缓存键
     *
     * @param key 缓存键
     */
    void evict(Object key);

    /**
     * 清空当前所有本地缓存
     */
    void clear();

    /**
     * 当前缓存条目估算数量
     *
     * @return 条目数
     */
    long size();
}
