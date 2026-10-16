package io.github.atengk.boot.redis.cache.spi;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 两级缓存（L1 本地内存缓存 + L2 Redis 分布式缓存）SPI 契约接口
 * 为未来微服务跨节点两级缓存与一致性广播预留解耦扩展规范
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface TwoLevelCache {

    /**
     * 获取缓存名称
     *
     * @return 缓存名称
     */
    String getName();

    /**
     * 获取缓存值
     *
     * @param key 缓存键
     * @param <T> 值泛型
     * @return 缓存值对象，不存在返回 null
     */
    <T> T get(Object key);

    /**
     * 获取缓存值并转换为目标类型
     *
     * @param key 缓存键
     * @param type 目标类型 Class
     * @param <T> 值泛型
     * @return 缓存值对象
     */
    <T> T get(Object key, Class<T> type);

    /**
     * 获取缓存值，若不存在则调用 loader 加载并回填两级缓存
     *
     * @param key 缓存键
     * @param loader 数据加载回调
     * @param <T> 值泛型
     * @return 缓存值
     */
    <T> T get(Object key, Callable<T> loader);

    /**
     * 写入两级缓存
     *
     * @param key 缓存键
     * @param value 缓存值
     */
    void put(Object key, Object value);

    /**
     * 写入两级缓存并指定过期时间
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl 过期时间
     */
    void put(Object key, Object value, Duration ttl);

    /**
     * 淘汰指定缓存键（同时清除本地 L1 与分布式 L2，并触发广播通知其他节点）
     *
     * @param key 缓存键
     */
    void evict(Object key);

    /**
     * 清空整块缓存
     */
    void clear();
}
