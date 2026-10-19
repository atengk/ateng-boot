package io.github.atengk.boot.redis.cache.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 工业级防击穿、防穿透、防雪崩缓存安全执行模板
 * 提供统一强类型泛型单键与批量 MGET 缓存门面，深度融合：
 * 1. 防击穿（Breakdown）：Redisson 分布式互斥锁 + DCL（Double-Checked Locking）双重检查；
 * 2. 防穿透（Penetration）：短 TTL 空值哨兵缓存（默认 5m）兜底，空值命中快速失败不查库；
 * 3. 防雪崩（Avalanche）：基准 TTL 附加动态 Jitter 随机抖动偏移量，打散热点集中失效；
 * 4. 容灾高可用（Resilience）：争锁超时（默认 3s）平滑穿透降级查库，保底业务可用不中断；
 * 5. 批量防 N+1（Batch MGET）：一次性网络 MGET 快速探测缺失键，批量分布式锁防死锁加锁，单次批量回源查库与 Pipeline 批量回写。
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class RedisCacheTemplate {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheTemplate.class);

    /**
     * 空值防穿透特殊哨兵标记字符串
     */
    public static final String NULL_SENTINEL = "__ATENG_CACHE_NULL__";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final RedisProperties redisProperties;

    /**
     * 容器依赖注入构造器
     *
     * @param redisTemplate RedisTemplate 实例
     * @param redissonClient Redisson 客户端
     * @param objectMapper 安全 JSON 序列化器
     * @param redisProperties 全局属性配置
     */
    public RedisCacheTemplate(RedisTemplate<String, Object> redisTemplate,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              RedisProperties redisProperties) {
        Assert.notNull(redisTemplate, "RedisTemplate 不能为空");
        Assert.notNull(redissonClient, "RedissonClient 不能为空");
        Assert.notNull(objectMapper, "ObjectMapper 不能为空");
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.redisProperties = redisProperties != null ? redisProperties : new RedisProperties();
    }

    // ==================== 1. 单键防卫门面 (getOrLoad) ====================

    /**
     * 单键防击穿、防穿透、防雪崩安全读取与回源底层核心模板
     */
    private <T> T doGetOrLoad(String key,
                              Function<Object, T> converter,
                              Function<String, T> loader,
                              Duration ttl,
                              Duration nullTtl) {
        Assert.hasText(key, "缓存 key 不能为空");
        Assert.notNull(loader, "回源 loader 不能为空");

        String realKey = buildKey(key);

        // 1. 第一重快速检查（Fast Path）
        Object rawVal = redisTemplate.opsForValue().get(realKey);
        if (NULL_SENTINEL.equals(rawVal)) {
            // 命中空值缓存，说明底层无此数据，防穿透生效直接返回 null
            return null;
        }
        if (rawVal != null) {
            return converter.apply(rawVal);
        }

        // 2. 缓存未命中（Cache Miss），获取分布式互斥锁防击穿
        String lockKey = buildLockKey(key);
        RLock lock = redissonClient.getLock(lockKey);
        Duration waitTime = getLockWaitTime();

        boolean locked = false;
        try {
            locked = lock.tryLock(waitTime.toMillis(), -1L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[RedisCacheTemplate] 争抢防击穿锁被中断，平滑降级查库: key={}", key);
        }

        if (locked) {
            try {
                // 3. DCL 第二重检查（Double-Checked Locking）
                Object dclVal = redisTemplate.opsForValue().get(realKey);
                if (NULL_SENTINEL.equals(dclVal)) {
                    return null;
                }
                if (dclVal != null) {
                    return converter.apply(dclVal);
                }

                // 4. 回源查库
                T loaded = loader.apply(key);
                if (loaded == null) {
                    // 查库为空，写入短 TTL 空值缓存防穿透
                    if (isCacheNullValues()) {
                        Duration actualNullTtl = calculateTtl(nullTtl, true);
                        redisTemplate.opsForValue().set(realKey, NULL_SENTINEL, actualNullTtl);
                    }
                    return null;
                } else {
                    // 查库成功，写入基准 TTL + Jitter 随机抖动防雪崩
                    Duration actualTtl = calculateTtl(ttl, false);
                    redisTemplate.opsForValue().set(realKey, loaded, actualTtl);
                    return loaded;
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } else {
            // 5. 争锁超时容灾降级（Resilience Pass-Through）：平滑直接查库，不向前端抛异常阻断业务
            log.warn("[RedisCacheTemplate] 争抢防击穿分布式锁超时，平滑降级直接穿透查库: key={}, waitTime={}", key, waitTime);
            T fallbackLoaded = loader.apply(key);
            if (fallbackLoaded != null) {
                try {
                    Duration actualTtl = calculateTtl(ttl, false);
                    redisTemplate.opsForValue().set(realKey, fallbackLoaded, actualTtl);
                } catch (Exception e) {
                    log.debug("[RedisCacheTemplate] 容灾回填缓存异常（忽略）: key={}", key, e);
                }
            }
            return fallbackLoaded;
        }
    }

    /**
     * 单键防击穿、防穿透、防雪崩安全读取与回源
     *
     * @param key 缓存键
     * @param clazz 目标返回类型 Class
     * @param loader 回源查库加载器
     * @param ttl 缓存生存时间（传 null 则使用全局 defaultTtl）
     * @param nullTtl 空值防穿透生存时间（传 null 则使用全局 nullTtl）
     * @param <T> 数据类型
     * @return 缓存或数据库中的数据对象（未查到返回 null）
     */
    public <T> T getOrLoad(String key, Class<T> clazz, Function<String, T> loader, Duration ttl, Duration nullTtl) {
        Assert.notNull(clazz, "目标类型 clazz 不能为空");
        return doGetOrLoad(key, rawVal -> convertValue(rawVal, clazz), loader, ttl, nullTtl);
    }

    /**
     * 单键读取与回源（采用配置默认 TTL 与 nullTtl）
     */
    public <T> T getOrLoad(String key, Class<T> clazz, Function<String, T> loader) {
        return getOrLoad(key, clazz, loader, null, null);
    }

    /**
     * 单键读取与回源（支持复杂泛型 TypeReference）
     */
    public <T> T getOrLoad(String key, TypeReference<T> typeRef, Function<String, T> loader, Duration ttl, Duration nullTtl) {
        Assert.notNull(typeRef, "目标类型 typeRef 不能为空");
        return doGetOrLoad(key, rawVal -> convertValue(rawVal, typeRef), loader, ttl, nullTtl);
    }

    /**
     * 单键读取与回源（支持复杂泛型 TypeReference，采用配置默认 TTL）
     */
    public <T> T getOrLoad(String key, TypeReference<T> typeRef, Function<String, T> loader) {
        return getOrLoad(key, typeRef, loader, null, null);
    }

    // ==================== 2. 批量防 N+1 门面 (batchGetOrLoad) ====================

    /**
     * 批量防 N+1 击穿、防穿透、防雪崩安全读取与回源通用底层模板
     */
    private <K, V> Map<K, V> doBatchGetOrLoad(List<K> keys,
                                              Function<K, String> keyMapper,
                                              Function<Object, V> converter,
                                              Function<List<K>, Map<K, V>> batchLoader,
                                              Duration ttl,
                                              Duration nullTtl) {
        if (CollectionUtils.isEmpty(keys)) {
            return Collections.emptyMap();
        }
        Assert.notNull(keyMapper, "keyMapper 不能为空");
        Assert.notNull(batchLoader, "batchLoader 不能为空");

        // 1. 去重并保持顺序
        List<K> uniqueKeys = new ArrayList<>(new LinkedHashSet<>(keys));
        Map<K, V> resultMap = new LinkedHashMap<>();

        // 2. 构造 Redis 物理键列表
        List<String> realKeys = new ArrayList<>(uniqueKeys.size());
        for (K k : uniqueKeys) {
            String strKey = keyMapper.apply(k);
            realKeys.add(buildKey(strKey));
        }

        // 3. 步骤 1：批量 MGET 探测
        List<Object> rawValues = redisTemplate.opsForValue().multiGet(realKeys);

        List<K> missingKeys = new ArrayList<>();
        for (int i = 0; i < uniqueKeys.size(); i++) {
            K k = uniqueKeys.get(i);
            Object rawVal = (rawValues != null && i < rawValues.size()) ? rawValues.get(i) : null;
            if (NULL_SENTINEL.equals(rawVal)) {
                // 空值哨兵，说明已知不存在，防穿透生效，跳过不查库
            } else if (rawVal != null) {
                resultMap.put(k, converter.apply(rawVal));
            } else {
                missingKeys.add(k);
            }
        }

        // 4. 若全部命中，极速返回
        if (missingKeys.isEmpty()) {
            return resultMap;
        }

        // 5. 存在缺失键，批量争锁防击穿（针对缺失键统一去重并排序后加锁，彻底杜绝死锁与重复锁）
        List<String> sortedLockKeys = missingKeys.stream()
                .map(k -> buildLockKey(keyMapper.apply(k)))
                .distinct()
                .sorted()
                .toList();

        RLock[] locks = sortedLockKeys.stream()
                .map(redissonClient::getLock)
                .toArray(RLock[]::new);

        RLock multiLock = redissonClient.getMultiLock(locks);
        Duration waitTime = getLockWaitTime();

        boolean locked = false;
        try {
            locked = multiLock.tryLock(waitTime.toMillis(), -1L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[RedisCacheTemplate] 批量防击穿加锁被中断，降级批量查库: size={}", missingKeys.size());
        }

        if (locked) {
            try {
                // 6. DCL 第二重 MGET
                List<String> missingRealKeys = missingKeys.stream()
                        .map(k -> buildKey(keyMapper.apply(k)))
                        .toList();

                List<Object> dclValues = redisTemplate.opsForValue().multiGet(missingRealKeys);
                List<K> stillMissingKeys = new ArrayList<>();

                for (int i = 0; i < missingKeys.size(); i++) {
                    K k = missingKeys.get(i);
                    Object dclVal = (dclValues != null && i < dclValues.size()) ? dclValues.get(i) : null;
                    if (NULL_SENTINEL.equals(dclVal)) {
                        // 已被其他并发线程置为空哨兵
                    } else if (dclVal != null) {
                        resultMap.put(k, converter.apply(dclVal));
                    } else {
                        stillMissingKeys.add(k);
                    }
                }

                // 7. 真正依然缺失的键，单次批量调用 batchLoader 回源，彻底杜绝 N+1！
                if (!stillMissingKeys.isEmpty()) {
                    Map<K, V> loadedMap = batchLoader.apply(stillMissingKeys);
                    if (loadedMap == null) {
                        loadedMap = Collections.emptyMap();
                    }

                    Duration actualTtl = calculateTtl(ttl, false);
                    Duration actualNullTtl = calculateTtl(nullTtl, true);
                    boolean cacheNull = isCacheNullValues();

                    // 8. Pipeline 批量回写 Redis
                    Map<K, V> finalLoadedMap = loadedMap;
                    redisTemplate.executePipelined(new SessionCallback<Object>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public Object execute(RedisOperations operations) throws DataAccessException {
                            for (K k : stillMissingKeys) {
                                String rKey = buildKey(keyMapper.apply(k));
                                V val = finalLoadedMap.get(k);
                                if (val != null) {
                                    operations.opsForValue().set(rKey, val, actualTtl);
                                } else if (cacheNull) {
                                    operations.opsForValue().set(rKey, NULL_SENTINEL, actualNullTtl);
                                }
                            }
                            return null;
                        }
                    });

                    // 9. 将查库结果汇总入最终结果
                    resultMap.putAll(finalLoadedMap);
                }
            } finally {
                if (multiLock.isHeldByCurrentThread()) {
                    multiLock.unlock();
                }
            }
        } else {
            // 10. 批量争锁超时容灾降级：平滑调用 batchLoader，不阻断业务
            log.warn("[RedisCacheTemplate] 批量防击穿加锁超时，平滑降级批量查库: size={}", missingKeys.size());
            Map<K, V> fallbackMap = batchLoader.apply(missingKeys);
            if (fallbackMap == null) {
                fallbackMap = Collections.emptyMap();
            }
            resultMap.putAll(fallbackMap);

            // 批量降级自愈回填（尽力而为，保障后续并发请求命中有序自愈）
            try {
                Duration actualTtl = calculateTtl(ttl, false);
                Duration actualNullTtl = calculateTtl(nullTtl, true);
                boolean cacheNull = isCacheNullValues();
                Map<K, V> finalFallbackMap = fallbackMap;

                redisTemplate.executePipelined(new SessionCallback<Object>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Object execute(RedisOperations operations) throws DataAccessException {
                        for (K k : missingKeys) {
                            String rKey = buildKey(keyMapper.apply(k));
                            V val = finalFallbackMap.get(k);
                            if (val != null) {
                                operations.opsForValue().set(rKey, val, actualTtl);
                            } else if (cacheNull) {
                                operations.opsForValue().set(rKey, NULL_SENTINEL, actualNullTtl);
                            }
                        }
                        return null;
                    }
                });
            } catch (Exception e) {
                log.debug("[RedisCacheTemplate] 批量降级回填缓存异常（忽略）: size={}", missingKeys.size(), e);
            }
        }

        return resultMap;
    }

    /**
     * 批量防 N+1 击穿、防穿透、防雪崩安全读取与回源
     *
     * @param keys 原始键列表
     * @param keyMapper 原始键到业务缓存字符串键的映射器
     * @param valueClass 目标值类型 Class
     * @param batchLoader 批量回源加载器（仅接收缺失的键，单次批量加载并返回 Map）
     * @param ttl 缓存生存时间
     * @param nullTtl 空值防穿透生存时间
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 映射结果 Map（空安全，保底返回非 null Map）
     */
    public <K, V> Map<K, V> batchGetOrLoad(List<K> keys,
                                           Function<K, String> keyMapper,
                                           Class<V> valueClass,
                                           Function<List<K>, Map<K, V>> batchLoader,
                                           Duration ttl,
                                           Duration nullTtl) {
        Assert.notNull(valueClass, "valueClass 不能为空");
        return doBatchGetOrLoad(keys, keyMapper, rawVal -> convertValue(rawVal, valueClass), batchLoader, ttl, nullTtl);
    }

    /**
     * 批量读取与回源（原始键直接为 String 类型）
     */
    public <V> Map<String, V> batchGetOrLoad(List<String> keys,
                                             Class<V> valueClass,
                                             Function<List<String>, Map<String, V>> batchLoader,
                                             Duration ttl,
                                             Duration nullTtl) {
        return batchGetOrLoad(keys, Function.identity(), valueClass, batchLoader, ttl, nullTtl);
    }

    /**
     * 批量读取与回源（采用配置默认 TTL）
     */
    public <K, V> Map<K, V> batchGetOrLoad(List<K> keys,
                                           Function<K, String> keyMapper,
                                           Class<V> valueClass,
                                           Function<List<K>, Map<K, V>> batchLoader) {
        return batchGetOrLoad(keys, keyMapper, valueClass, batchLoader, null, null);
    }

    /**
     * 批量读取与回源（原始键直接为 String，采用配置默认 TTL）
     */
    public <V> Map<String, V> batchGetOrLoad(List<String> keys,
                                             Class<V> valueClass,
                                             Function<List<String>, Map<String, V>> batchLoader) {
        return batchGetOrLoad(keys, Function.identity(), valueClass, batchLoader, null, null);
    }

    /**
     * 批量防 N+1 击穿、防穿透、防雪崩安全读取与回源（支持复杂泛型 TypeReference）
     *
     * @param keys 原始键列表
     * @param keyMapper 原始键到业务缓存字符串键的映射器
     * @param typeRef 目标值复杂泛型 TypeReference
     * @param batchLoader 批量回源加载器
     * @param ttl 缓存生存时间
     * @param nullTtl 空值防穿透生存时间
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 映射结果 Map
     */
    public <K, V> Map<K, V> batchGetOrLoad(List<K> keys,
                                           Function<K, String> keyMapper,
                                           TypeReference<V> typeRef,
                                           Function<List<K>, Map<K, V>> batchLoader,
                                           Duration ttl,
                                           Duration nullTtl) {
        Assert.notNull(typeRef, "typeRef 不能为空");
        return doBatchGetOrLoad(keys, keyMapper, rawVal -> convertValue(rawVal, typeRef), batchLoader, ttl, nullTtl);
    }

    /**
     * 批量读取与回源（支持复杂泛型 TypeReference，采用配置默认 TTL）
     */
    public <K, V> Map<K, V> batchGetOrLoad(List<K> keys,
                                           Function<K, String> keyMapper,
                                           TypeReference<V> typeRef,
                                           Function<List<K>, Map<K, V>> batchLoader) {
        return batchGetOrLoad(keys, keyMapper, typeRef, batchLoader, null, null);
    }

    /**
     * 批量读取与回源（原始键为 String，支持复杂泛型 TypeReference）
     */
    public <V> Map<String, V> batchGetOrLoad(List<String> keys,
                                             TypeReference<V> typeRef,
                                             Function<List<String>, Map<String, V>> batchLoader,
                                             Duration ttl,
                                             Duration nullTtl) {
        return batchGetOrLoad(keys, Function.identity(), typeRef, batchLoader, ttl, nullTtl);
    }

    /**
     * 批量读取与回源（原始键为 String，支持复杂泛型 TypeReference，采用配置默认 TTL）
     */
    public <V> Map<String, V> batchGetOrLoad(List<String> keys,
                                             TypeReference<V> typeRef,
                                             Function<List<String>, Map<String, V>> batchLoader) {
        return batchGetOrLoad(keys, Function.identity(), typeRef, batchLoader, null, null);
    }

    // ==================== 3. 对称基础运维门面 (防线 1) ====================

    /**
     * 主动根据 key 查询缓存（无回源）
     */
    public <T> T get(String key, Class<T> clazz) {
        Assert.hasText(key, "缓存 key 不能为空");
        Assert.notNull(clazz, "clazz 不能为空");
        Object val = redisTemplate.opsForValue().get(buildKey(key));
        if (NULL_SENTINEL.equals(val) || val == null) {
            return null;
        }
        return convertValue(val, clazz);
    }

    /**
     * 主动根据 key 查询复杂泛型缓存（无回源）
     */
    public <T> T get(String key, TypeReference<T> typeRef) {
        Assert.hasText(key, "缓存 key 不能为空");
        Assert.notNull(typeRef, "typeRef 不能为空");
        Object val = redisTemplate.opsForValue().get(buildKey(key));
        if (NULL_SENTINEL.equals(val) || val == null) {
            return null;
        }
        return convertValue(val, typeRef);
    }

    /**
     * 主动写入缓存（自定义 TTL，并自动应用 Jitter 抖动）
     */
    public void put(String key, Object value, Duration ttl) {
        Assert.hasText(key, "缓存 key 不能为空");
        String realKey = buildKey(key);
        if (value == null) {
            if (isCacheNullValues()) {
                Duration actualNullTtl = calculateTtl(null, true);
                redisTemplate.opsForValue().set(realKey, NULL_SENTINEL, actualNullTtl);
            }
        } else {
            Duration actualTtl = calculateTtl(ttl, false);
            redisTemplate.opsForValue().set(realKey, value, actualTtl);
        }
    }

    /**
     * 主动写入缓存（采用默认 TTL 与抖动）
     */
    public void put(String key, Object value) {
        put(key, value, null);
    }

    /**
     * 单键主动驱逐淘汰
     */
    public boolean evict(String key) {
        Assert.hasText(key, "缓存 key 不能为空");
        Boolean deleted = redisTemplate.delete(buildKey(key));
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * 批量键主动驱逐淘汰
     */
    public long batchEvict(Collection<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            return 0L;
        }
        List<String> realKeys = keys.stream().map(this::buildKey).toList();
        Long deleted = redisTemplate.delete(realKeys);
        return deleted != null ? deleted : 0L;
    }

    /**
     * 判断缓存键是否存在且包含真实有效业务数据（防穿透空哨兵视为不存在）
     *
     * @param key 缓存键
     * @return 若物理存在且不为空哨兵返回 true；否则返回 false
     */
    public boolean hasKey(String key) {
        Assert.hasText(key, "缓存 key 不能为空");
        Object val = redisTemplate.opsForValue().get(buildKey(key));
        return val != null && !NULL_SENTINEL.equals(val);
    }

    // ==================== 4. 辅助与工具方法 ====================

    /**
     * 构建受管缓存键，统一前置应用全局 keyPrefix
     */
    public String buildKey(String rawKey) {
        Assert.hasText(rawKey, "rawKey 不能为空");
        String prefix = redisProperties.getKeyPrefix();
        if (StringUtils.hasText(prefix) && !rawKey.startsWith(prefix)) {
            return prefix + rawKey;
        }
        return rawKey;
    }

    /**
     * 构建防击穿分布式互斥锁 Key
     */
    public String buildLockKey(String rawKey) {
        Assert.hasText(rawKey, "rawKey 不能为空");
        String prefix = redisProperties.getKeyPrefix();
        String lockName = "lock:cache:" + rawKey;
        if (StringUtils.hasText(prefix) && !lockName.startsWith(prefix)) {
            return prefix + lockName;
        }
        return lockName;
    }

    /**
     * 计算带随机抖动的实际生存时间
     */
    public Duration calculateTtl(Duration specificTtl, boolean isNull) {
        RedisProperties.CacheProperties cacheProps = redisProperties.getCache();
        Duration baseTtl;
        if (isNull) {
            baseTtl = specificTtl != null ? specificTtl : cacheProps.getNullTtl();
        } else {
            baseTtl = specificTtl != null ? specificTtl : cacheProps.getDefaultTtl();
        }
        if (baseTtl == null) {
            baseTtl = isNull ? Duration.ofMinutes(5) : Duration.ofMinutes(30);
        }

        // 空值缓存不叠加抖动，直接使用短 TTL；若未启用 Jitter 也直接返回
        if (!cacheProps.isJitterEnabled() || isNull) {
            return baseTtl;
        }

        Duration maxJitter = cacheProps.getMaxJitter();
        if (maxJitter == null || maxJitter.isZero() || maxJitter.isNegative()) {
            return baseTtl;
        }

        long jitterMillis = ThreadLocalRandom.current().nextLong(1, Math.max(2, maxJitter.toMillis() + 1));
        return baseTtl.plusMillis(jitterMillis);
    }

    /**
     * 反序列化辅助转换
     */
    @SuppressWarnings("unchecked")
    private <T> T convertValue(Object rawValue, Class<T> clazz) {
        if (rawValue == null || NULL_SENTINEL.equals(rawValue)) {
            return null;
        }
        if (clazz.isInstance(rawValue)) {
            return clazz.cast(rawValue);
        }
        return objectMapper.convertValue(rawValue, clazz);
    }

    /**
     * 复杂泛型反序列化辅助转换
     */
    @SuppressWarnings("unchecked")
    private <T> T convertValue(Object rawValue, TypeReference<T> typeRef) {
        if (rawValue == null || NULL_SENTINEL.equals(rawValue)) {
            return null;
        }
        return objectMapper.convertValue(rawValue, typeRef);
    }

    private boolean isCacheNullValues() {
        return redisProperties.getCache().isCacheNullValues();
    }

    private Duration getLockWaitTime() {
        Duration waitTime = redisProperties.getCache().getLockWaitTime();
        return waitTime != null ? waitTime : Duration.ofSeconds(3);
    }
}
