package io.github.atengk.boot.redis.cache.twolevel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCache;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工业级生产就绪两级缓存默认实现 (DefaultTwoLevelCache)
 * 深度融合：
 * 1. L1 内存极速读取（纳秒级命中，消除 Redis 网络 IO）；
 * 2. L2 Redis 分布式兜底（跨节点共享，支持 TTL 与防雪崩 Jitter 抖动）；
 * 3. 防穿透哨兵（NULL_SENTINEL 短 TTL 缓存）；
 * 4. 游标安全扫描批量清理（严格游标 scan，彻底杜绝 keys * 阻塞事故）；
 * 5. 跨节点集中 Pub/Sub 广播失效与 instanceId 防自重入死循环。
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class DefaultTwoLevelCache implements TwoLevelCache {

    private static final Logger log = LoggerFactory.getLogger(DefaultTwoLevelCache.class);

    /**
     * 空值防穿透特殊哨兵标记字符串
     */
    public static final String NULL_SENTINEL = "__ATENG_CACHE_NULL__";

    /**
     * 分段并发条带锁槽位数量（消除实例级全局锁引发的队头阻塞）
     */
    private static final int STRIPE_COUNT = 64;

    private final String name;
    private final L1MemoryCache l1Cache;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final String topic;
    private final RedisProperties redisProperties;
    private final Duration defaultL2Ttl;
    private final boolean cacheNullValues;
    private final Duration nullTtl;
    private final ReentrantLock[] stripeLocks;

    public DefaultTwoLevelCache(String name,
                               L1MemoryCache l1Cache,
                               RedisTemplate<String, Object> redisTemplate,
                               ObjectMapper objectMapper,
                               String instanceId,
                               String topic,
                               RedisProperties redisProperties,
                               Duration defaultL2Ttl,
                               boolean cacheNullValues,
                               Duration nullTtl) {
        Assert.hasText(name, "cacheName 不能为空");
        Assert.notNull(l1Cache, "l1Cache 不能为空");
        Assert.notNull(redisTemplate, "redisTemplate 不能为空");
        Assert.notNull(objectMapper, "objectMapper 不能为空");
        Assert.hasText(instanceId, "instanceId 不能为空");
        Assert.hasText(topic, "topic 不能为空");

        this.name = name;
        this.l1Cache = l1Cache;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.topic = topic;
        this.redisProperties = redisProperties;
        this.defaultL2Ttl = defaultL2Ttl != null ? defaultL2Ttl : Duration.ofMinutes(30);
        this.cacheNullValues = cacheNullValues;
        this.nullTtl = nullTtl != null ? nullTtl : Duration.ofMinutes(5);

        this.stripeLocks = new ReentrantLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.stripeLocks[i] = new ReentrantLock();
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public Object getNativeCache() {
        return this.l1Cache;
    }

    @Override
    public ValueWrapper get(Object key) {
        Assert.notNull(key, "key 不能为空");
        Object l1Key = toL1Key(key);

        // 1. 优先探测本地 L1 内存缓存
        Object l1Val = l1Cache.get(l1Key);
        if (l1Val != null) {
            return toValueWrapper(l1Val);
        }

        // 2. 本地未命中，穿透查询 L2 Redis
        String redisKey = buildRedisKey(key);
        Object l2Val = redisTemplate.opsForValue().get(redisKey);
        if (l2Val != null) {
            // 回填本地 L1 内存
            l1Cache.put(l1Key, l2Val);
            return toValueWrapper(l2Val);
        }

        // 3. 两级均未命中
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object val = wrapper.get();
        if (val == null) {
            return null;
        }
        if (type == null || type.isInstance(val)) {
            return (T) val;
        }
        return objectMapper.convertValue(val, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }

        // 针对单节点的条带化双重检查加锁（DCL），杜绝实例级全局锁引发的排队阻塞
        ReentrantLock lock = getStripeLock(key);
        lock.lock();
        try {
            wrapper = get(key);
            if (wrapper != null) {
                return (T) wrapper.get();
            }

            T value;
            try {
                value = valueLoader.call();
            } catch (Exception ex) {
                throw new ValueRetrievalException(key, valueLoader, ex);
            }

            put(key, value);
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(Object key, Object value) {
        put(key, value, this.defaultL2Ttl);
    }

    @Override
    public void put(Object key, Object value, Duration ttl) {
        Assert.notNull(key, "key 不能为空");
        Object l1Key = toL1Key(key);
        String redisKey = buildRedisKey(key);

        if (value == null) {
            if (this.cacheNullValues) {
                l1Cache.put(l1Key, NULL_SENTINEL);
                Duration effectiveNullTtl = this.nullTtl != null ? this.nullTtl : Duration.ofMinutes(5);
                redisTemplate.opsForValue().set(redisKey, NULL_SENTINEL, effectiveNullTtl);
                publishEvictMessage(l1Key, false);
            } else {
                evict(key);
            }
            return;
        }

        // 1. 写入本地 L1 缓存
        l1Cache.put(l1Key, value);

        // 2. 写入分布式 L2 Redis 缓存（计算随机 Jitter 抖动）
        Duration actualTtl = calculateTtl(ttl);
        if (actualTtl != null && !actualTtl.isZero() && !actualTtl.isNegative()) {
            redisTemplate.opsForValue().set(redisKey, value, actualTtl);
        } else {
            redisTemplate.opsForValue().set(redisKey, value);
        }

        // 3. 广播跨节点失效通知
        publishEvictMessage(l1Key, false);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        Assert.notNull(key, "key 不能为空");
        ReentrantLock lock = getStripeLock(key);
        lock.lock();
        try {
            ValueWrapper existing = get(key);
            if (existing != null) {
                return existing;
            }
            put(key, value);
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void evict(Object key) {
        Assert.notNull(key, "key 不能为空");
        Object l1Key = toL1Key(key);

        // 1. 淘汰本地 L1 内存（同时防御原始 key 与规范化 key）
        l1Cache.evict(l1Key);
        if (!key.equals(l1Key)) {
            l1Cache.evict(key);
        }

        // 2. 删除分布式 L2 Redis 键
        String redisKey = buildRedisKey(key);
        redisTemplate.delete(redisKey);

        // 3. 广播跨节点失效通知
        publishEvictMessage(l1Key, false);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        Assert.notNull(key, "key 不能为空");
        boolean present = (get(key) != null);
        evict(key);
        return present;
    }

    @Override
    public void clear() {
        // 1. 清空本地 L1 内存
        l1Cache.clear();

        // 2. 游标安全扫描清理 L2 Redis 当前空间所有键（严格杜绝 keys *）
        String pattern = buildRedisKey("*");
        deleteKeysByPattern(pattern);

        // 3. 广播跨节点全量清理通知
        publishEvictMessage(null, true);
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    @Override
    public void evictLocal(Object key) {
        if (key != null) {
            Object l1Key = toL1Key(key);
            l1Cache.evict(l1Key);
            if (!key.equals(l1Key)) {
                l1Cache.evict(key);
            }
        }
    }

    @Override
    public void clearLocal() {
        l1Cache.clear();
    }

    /**
     * 将 L1 原始值包装为统一的 Spring Cache ValueWrapper
     */
    private ValueWrapper toValueWrapper(Object val) {
        if (val == null) {
            return null;
        }
        if (NULL_SENTINEL.equals(val)) {
            return new SimpleValueWrapper(null);
        }
        return new SimpleValueWrapper(val);
    }

    /**
     * 将泛型缓存键规范化（基本类型与数值转为统一 String），消除跨节点 JSON 反序列化类型漂移隐患
     *
     * @param key 原始缓存键
     * @return 规范化后的 L1 键对象
     */
    protected Object toL1Key(Object key) {
        if (key == null) {
            return null;
        }
        if (key instanceof String || key instanceof Number || key instanceof Boolean || key instanceof Character) {
            return String.valueOf(key);
        }
        return key;
    }

    /**
     * 根据缓存键获取分段条带锁
     */
    private ReentrantLock getStripeLock(Object key) {
        int hash = key != null ? key.hashCode() : 0;
        hash = hash ^ (hash >>> 16);
        int index = (hash & 0x7fffffff) % STRIPE_COUNT;
        return stripeLocks[index];
    }

    /**
     * 广播失效消息至 Redis Pub/Sub 集中主题
     */
    private void publishEvictMessage(Object key, boolean clearAll) {
        try {
            CacheEvictMessage msg = new CacheEvictMessage(this.name, key, this.instanceId, clearAll);
            String actualTopic = redisProperties != null ? redisProperties.buildKey(this.topic) : this.topic;
            redisTemplate.convertAndSend(actualTopic, msg);
        } catch (Exception ex) {
            log.warn("两级缓存广播失效消息失败 [topic={}, cacheName={}, key={}, clearAll={}]",
                    topic, name, key, clearAll, ex);
        }
    }

    /**
     * 构建受管 Redis 键名，格式为 {keyPrefix}{cacheName}:{key}
     */
    public String buildRedisKey(Object key) {
        String rawKey = this.name + ":" + key;
        return redisProperties != null ? redisProperties.buildKey(rawKey) : rawKey;
    }

    /**
     * 基于游标 scan 安全分批删除 Redis 键（兼容 Standalone 与 Redis Cluster 集群模式）
     */
    private void deleteKeysByPattern(String pattern) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                List<String> keysToDelete = new ArrayList<>();
                while (cursor.hasNext()) {
                    keysToDelete.add(cursor.next());
                    if (keysToDelete.size() >= 100) {
                        redisTemplate.delete(keysToDelete);
                        keysToDelete.clear();
                    }
                }
                if (!keysToDelete.isEmpty()) {
                    redisTemplate.delete(keysToDelete);
                }
            }
        } catch (Exception ex) {
            log.warn("两级缓存按模式清理 Redis 键失败 [pattern={}]", pattern, ex);
        }
    }

    /**
     * 计算叠加 Jitter 随机抖动后的 L2 过期时间
     */
    private Duration calculateTtl(Duration specificTtl) {
        RedisProperties.TwoLevelCacheProperties.L2Properties l2Props =
                (redisProperties != null && redisProperties.getTwoLevel() != null)
                        ? redisProperties.getTwoLevel().getL2() : null;

        Duration baseTtl = specificTtl != null ? specificTtl : (l2Props != null ? l2Props.getDefaultTtl() : defaultL2Ttl);
        if (baseTtl == null) {
            return null;
        }

        if (l2Props != null && l2Props.isJitterEnabled()) {
            Duration maxJitter = l2Props.getMaxJitter();
            if (maxJitter != null && !maxJitter.isZero() && !maxJitter.isNegative()) {
                long jitterMillis = ThreadLocalRandom.current().nextLong(1, Math.max(2, maxJitter.toMillis() + 1));
                return baseTtl.plusMillis(jitterMillis);
            }
        }

        return baseTtl;
    }

    public L1MemoryCache getL1Cache() {
        return l1Cache;
    }

    public String getInstanceId() {
        return instanceId;
    }
}
