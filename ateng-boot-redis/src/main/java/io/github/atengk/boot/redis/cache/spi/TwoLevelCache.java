package io.github.atengk.boot.redis.cache.spi;

import org.springframework.cache.Cache;

import java.time.Duration;

/**
 * 两级缓存（L1 本地内存缓存 + L2 Redis 分布式缓存）SPI 契约接口
 * 继承 Spring 原生 Cache 接口，无缝支持声明式注解与编程式门面调用，扩展动态 TTL 写入与本地定向失效能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface TwoLevelCache extends Cache {

    /**
     * 写入两级缓存并指定 L2 Redis 独立过期时间
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl L2 过期时间
     */
    void put(Object key, Object value, Duration ttl);

    /**
     * 仅淘汰本地 L1 缓存（供跨节点失效广播消费端调用，避免二次广播）
     *
     * @param key 缓存键
     */
    void evictLocal(Object key);

    /**
     * 仅清空本地 L1 缓存（供跨节点失效广播消费端调用，避免二次广播）
     */
    void clearLocal();

    /**
     * 获取指定类型的缓存值（便捷强类型转换，不存在或命中防穿透空值时返回 null）
     *
     * @param key 缓存键
     * @param type 目标类型
     * @param <T> 泛型
     * @return 转换后的值
     */
    @Override
    <T> T get(Object key, Class<T> type);
}
