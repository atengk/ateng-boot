package io.github.atengk.boot.redis.cache.twolevel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCache;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCacheFactory;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.support.AbstractCacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 生产级内存+Redis 两级缓存管理器 (TwoLevelCacheManager)
 * 桥接 Spring Cache 抽象体系，支持直接在业务 Service 声明 {@code @Cacheable} / {@code @CacheEvict}，
 * 同时支持编程式门面调用与跨节点 Pub/Sub 广播自动协同失效。
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class TwoLevelCacheManager extends AbstractCacheManager implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCacheManager.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisProperties redisProperties;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final String topic;
    private final ConcurrentMap<String, TwoLevelCache> twoLevelCacheMap = new ConcurrentHashMap<>();

    private MessageListener messageListener;

    public TwoLevelCacheManager(RedisTemplate<String, Object> redisTemplate,
                               RedisProperties redisProperties,
                               RedisMessageListenerContainer listenerContainer) {
        Assert.notNull(redisTemplate, "redisTemplate 不能为空");
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties != null ? redisProperties : new RedisProperties();
        this.listenerContainer = listenerContainer;
        this.objectMapper = RedisSerializerFactory.createSafeObjectMapper();
        this.instanceId = UUID.randomUUID().toString().replace("-", "");

        RedisProperties.TwoLevelCacheProperties twoLevelProps = this.redisProperties.getTwoLevel();
        this.topic = (twoLevelProps != null && StringUtils.hasText(twoLevelProps.getTopic()))
                ? twoLevelProps.getTopic()
                : "ateng:cache:evict:topic";
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        initPubSubListener();
    }

    @Override
    protected Collection<? extends Cache> loadCaches() {
        return Collections.unmodifiableCollection(twoLevelCacheMap.values());
    }

    @Override
    protected Cache getMissingCache(String name) {
        return twoLevelCacheMap.computeIfAbsent(name, this::createTwoLevelCache);
    }

    /**
     * 获取强类型两级缓存门面
     *
     * @param name 缓存空间名称
     * @return TwoLevelCache 实例
     */
    public TwoLevelCache getTwoLevelCache(String name) {
        return (TwoLevelCache) getCache(name);
    }

    /**
     * 动态创建两级缓存实例
     */
    private TwoLevelCache createTwoLevelCache(String name) {
        RedisProperties.TwoLevelCacheProperties twoLevelProps = redisProperties.getTwoLevel();
        RedisProperties.TwoLevelCacheProperties.L1Properties l1Props =
                twoLevelProps != null ? twoLevelProps.getL1() : new RedisProperties.TwoLevelCacheProperties.L1Properties();
        RedisProperties.TwoLevelCacheProperties.L2Properties l2Props =
                twoLevelProps != null ? twoLevelProps.getL2() : new RedisProperties.TwoLevelCacheProperties.L2Properties();

        // 1. 解析 L1 本地缓存容量与 TTL
        long l1MaxSize = l1Props.getMaxSizes().getOrDefault(name, l1Props.getDefaultMaxSize());
        Duration l1Ttl = l1Props.getTtls().getOrDefault(name, l1Props.getDefaultTtl());
        L1MemoryCache l1Cache = L1MemoryCacheFactory.create(l1MaxSize, l1Ttl);

        // 2. 解析 L2 分布式缓存配置
        Duration l2Ttl = l2Props.getTtls().getOrDefault(name, l2Props.getDefaultTtl());
        if (l2Ttl == null && redisProperties.getCache() != null) {
            l2Ttl = redisProperties.getCache().getDefaultTtl();
        }

        boolean cacheNullValues = l2Props.isCacheNullValues();
        Duration nullTtl = l2Props.getNullTtl();
        if (nullTtl == null && redisProperties.getCache() != null) {
            nullTtl = redisProperties.getCache().getNullTtl();
        }

        log.info("初始化两级缓存空间 [name={}, l1MaxSize={}, l1Ttl={}, l2Ttl={}, instanceId={}]",
                name, l1MaxSize, l1Ttl, l2Ttl, instanceId);

        return new DefaultTwoLevelCache(
                name,
                l1Cache,
                redisTemplate,
                objectMapper,
                instanceId,
                topic,
                redisProperties,
                l2Ttl,
                cacheNullValues,
                nullTtl
        );
    }

    /**
     * 初始化 Redis Pub/Sub 广播监听容器
     */
    private void initPubSubListener() {
        if (listenerContainer == null) {
            log.info("未提供 RedisMessageListenerContainer，两级缓存跨节点失效广播监听处于离线模式");
            return;
        }

        String actualTopic = redisProperties.buildKey(this.topic);
        this.messageListener = (message, pattern) -> {
            try {
                byte[] body = message.getBody();
                if (body == null || body.length == 0) {
                    return;
                }
                CacheEvictMessage evictMessage = objectMapper.readValue(body, CacheEvictMessage.class);
                handleEvictMessage(evictMessage);
            } catch (Exception ex) {
                log.warn("解析两级缓存广播失效消息异常", ex);
            }
        };

        listenerContainer.addMessageListener(this.messageListener, new ChannelTopic(actualTopic));
        log.info("两级缓存成功订阅跨节点失效广播主题 [topic={}, instanceId={}]", actualTopic, instanceId);
    }

    /**
     * 处理跨节点失效广播消息
     *
     * @param evictMessage 失效报文载荷
     */
    public void handleEvictMessage(CacheEvictMessage evictMessage) {
        if (evictMessage == null) {
            return;
        }

        // 核心防自重入防线：若广播消息由本节点发送，直接忽略
        if (this.instanceId.equals(evictMessage.getInstanceId())) {
            log.trace("忽略自身发送的两级缓存广播消息 [cacheName={}, key={}]",
                    evictMessage.getCacheName(), evictMessage.getKey());
            return;
        }

        TwoLevelCache cache = twoLevelCacheMap.get(evictMessage.getCacheName());
        if (cache == null) {
            return;
        }

        if (evictMessage.isClearAll()) {
            log.debug("收到跨节点全量清理广播，清除本地 L1 缓存 [cacheName={}, sourceInstanceId={}]",
                    evictMessage.getCacheName(), evictMessage.getInstanceId());
            cache.clearLocal();
        } else {
            log.debug("收到跨节点定向失效广播，淘汰本地 L1 缓存键 [cacheName={}, key={}, sourceInstanceId={}]",
                    evictMessage.getCacheName(), evictMessage.getKey(), evictMessage.getInstanceId());
            cache.evictLocal(evictMessage.getKey());
        }
    }

    @Override
    public void destroy() {
        if (listenerContainer != null && messageListener != null) {
            try {
                String actualTopic = redisProperties.buildKey(this.topic);
                listenerContainer.removeMessageListener(messageListener, new ChannelTopic(actualTopic));
                log.info("两级缓存注销跨节点失效广播主题 [topic={}]", actualTopic);
            } catch (Exception ex) {
                log.warn("注销两级缓存监听器失败", ex);
            }
        }

        for (TwoLevelCache cache : twoLevelCacheMap.values()) {
            cache.clearLocal();
        }
        twoLevelCacheMap.clear();
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getTopic() {
        return topic;
    }

    public Map<String, TwoLevelCache> getTwoLevelCacheMap() {
        return Collections.unmodifiableMap(twoLevelCacheMap);
    }
}
