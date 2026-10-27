package io.github.atengk.boot.redis.autoconfigure;

import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.bloom.BloomFilterBootstrap;
import io.github.atengk.boot.redis.bloom.RedisBloomFilter;
import io.github.atengk.boot.redis.cache.JitterTtlFunction;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.bootstrap.DelayedQueueBootstrap;
import io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler;
import io.github.atengk.boot.redis.delay.spi.LoggingDelayedQueueFailureHandler;
import io.github.atengk.boot.redis.lock.aspect.DistributedLockAspect;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.util.RedisLockUtil;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.pubsub.RedisMessagePublisher;
import io.github.atengk.boot.redis.pubsub.bootstrap.ChannelTopicBootstrap;
import io.github.atengk.boot.redis.ratelimit.RedisRateLimiterClient;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import io.github.atengk.boot.redis.util.RedisUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 增强基础设施与分布式协同自动装配类
 * 实现 Spring Data Redis (Lettuce) 与 Redisson 双核客户端融合装配
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration
@ConditionalOnClass({RedisOperations.class, RedissonClient.class})
@ConditionalOnProperty(prefix = "ateng.boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RedisProperties.class)
public class AtengRedisAutoConfiguration {

    private static final String SCHEMA_PREFIX = "redis://";
    private static final String SCHEMA_SSL_PREFIX = "rediss://";

    /**
     * 装配纯净无 @class 污染的安全 JSON 序列化器
     *
     * @return Redis 序列化器
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisValueSerializer")
    public RedisSerializer<Object> redisValueSerializer() {
        return RedisSerializerFactory.createJsonSerializer();
    }

    /**
     * 装配强类型 RedisTemplate，统一 Key/Value 序列化标准
     *
     * @param connectionFactory Redis 连接工厂
     * @param redisValueSerializer 值序列化器
     * @return 配置完毕的 RedisTemplate
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       @Qualifier("redisValueSerializer") RedisSerializer<Object> redisValueSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        template.setValueSerializer(redisValueSerializer);
        template.setHashValueSerializer(redisValueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 装配全能 Redis 操作门面工具 RedisUtil
     *
     * @param redisTemplate RedisTemplate 实例
     * @param redisProperties Redis 核心配置属性
     * @return RedisUtil 门面实例
     */
    @Bean
    @ConditionalOnMissingBean(RedisUtil.class)
    public RedisUtil redisUtil(RedisTemplate<String, Object> redisTemplate, RedisProperties redisProperties) {
        return new RedisUtil(redisTemplate, redisProperties);
    }

    /**
     * 装配编程式分布式锁执行模板 DistributedLockTemplate 并初始化 RedisLockUtil 门面
     *
     * @param redissonClient Redisson 客户端
     * @param redisProperties 属性配置
     * @return 分布式锁模板
     */
    @Bean
    @ConditionalOnMissingBean(DistributedLockTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockTemplate distributedLockTemplate(RedissonClient redissonClient, RedisProperties redisProperties) {
        DistributedLockTemplate template = new DistributedLockTemplate(redissonClient, redisProperties);
        RedisLockUtil.init(template);
        return template;
    }

    /**
     * 装配声明式分布式锁切面 DistributedLockAspect
     *
     * @param distributedLockTemplate 分布式锁模板
     * @param redisProperties 属性配置
     * @return 分布式锁切面
     */
    @Bean
    @ConditionalOnMissingBean(DistributedLockAspect.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockAspect distributedLockAspect(DistributedLockTemplate distributedLockTemplate) {
        return new DistributedLockAspect(distributedLockTemplate);
    }

    /**
     * 装配编程式延时队列门面 RedisDelayedQueue
     *
     * @param redissonClient Redisson 客户端
     * @param redisProperties 属性配置
     * @return RedisDelayedQueue 门面
     */
    @Bean
    @ConditionalOnMissingBean(RedisDelayedQueue.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.delay-queue", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisDelayedQueue redisDelayedQueue(RedissonClient redissonClient, RedisProperties redisProperties) {
        return new RedisDelayedQueue(redissonClient, RedisSerializerFactory.createSafeObjectMapper(), redisProperties);
    }

    /**
     * 装配延时队列默认消费失败处理器 LoggingDelayedQueueFailureHandler
     *
     * @return 默认延时队列失败处理器
     */
    @Bean
    @ConditionalOnMissingBean(DelayedQueueFailureHandler.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.delay-queue", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DelayedQueueFailureHandler delayedQueueFailureHandler() {
        return new LoggingDelayedQueueFailureHandler();
    }

    /**
     * 装配声明式延时队列容器生命周期调度器 DelayedQueueBootstrap
     *
     * @param redisDelayedQueue 延时队列门面
     * @param redisProperties 属性配置
     * @param failureHandlerProvider 失败处理器提供者（可选）
     * @return 延时队列生命周期调度器
     */
    @Bean
    @ConditionalOnMissingBean(DelayedQueueBootstrap.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.delay-queue", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DelayedQueueBootstrap delayedQueueBootstrap(RedisDelayedQueue redisDelayedQueue,
                                                       RedisProperties redisProperties,
                                                       ObjectProvider<DelayedQueueFailureHandler> failureHandlerProvider) {
        return new DelayedQueueBootstrap(redisDelayedQueue, RedisSerializerFactory.createSafeObjectMapper(),
                redisProperties, failureHandlerProvider.getIfAvailable());
    }

    /**
     * 装配 Redis 主题消息监听容器 RedisMessageListenerContainer（按需懒加载）
     *
     * @param connectionFactory Redis 连接工厂
     * @return 监听容器
     */
    @Bean
    @org.springframework.context.annotation.Lazy
    @ConditionalOnMissingBean(RedisMessageListenerContainer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.pub-sub", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 装配轻量 Pub/Sub 广播消息发布与动态监听门面 RedisMessagePublisher
     *
     * @param redisTemplate RedisTemplate 实例
     * @param containerProvider 监听容器提供者（可选）
     * @return RedisMessagePublisher 门面
     */
    @Bean
    @ConditionalOnMissingBean(RedisMessagePublisher.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.pub-sub", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessagePublisher redisMessagePublisher(RedisTemplate<String, Object> redisTemplate,
                                                       ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        return new RedisMessagePublisher(redisTemplate, containerProvider.getIfAvailable());
    }

    /**
     * 装配声明式 Redis Pub/Sub 广播主题生命周期调度器 ChannelTopicBootstrap
     *
     * @param containerProvider 监听容器提供者
     * @param redisProperties 属性配置
     * @return 广播主题调度器
     */
    @Bean
    @ConditionalOnMissingBean(ChannelTopicBootstrap.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.pub-sub", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChannelTopicBootstrap channelTopicBootstrap(ObjectProvider<RedisMessageListenerContainer> containerProvider,
                                                       RedisProperties redisProperties) {
        return new ChannelTopicBootstrap(containerProvider, RedisSerializerFactory.createSafeObjectMapper(), redisProperties);
    }

    /**
     * 装配分布式业务级并发限流客户端 RedisRateLimiterClient
     *
     * @param redissonClient Redisson 客户端
     * @return RedisRateLimiterClient 门面
     */
    @Bean
    @ConditionalOnMissingBean(RedisRateLimiterClient.class)
    public RedisRateLimiterClient redisRateLimiterClient(RedissonClient redissonClient) {
        return new RedisRateLimiterClient(redissonClient);
    }

    /**
     * 装配分布式原子自增、发号与滑动窗口工具 RedisAtomicUtil
     *
     * @param redisTemplate RedisTemplate 实例
     * @return RedisAtomicUtil 门面
     */
    @Bean
    @ConditionalOnMissingBean(RedisAtomicUtil.class)
    public RedisAtomicUtil redisAtomicUtil(RedisTemplate<String, Object> redisTemplate) {
        return new RedisAtomicUtil(redisTemplate);
    }

    /**
     * 装配分布式布隆过滤器操作门面 RedisBloomFilter
     *
     * @param redissonClient Redisson 客户端
     * @param redisProperties 属性配置
     * @return RedisBloomFilter 门面
     */
    @Bean
    @ConditionalOnMissingBean(RedisBloomFilter.class)
    public RedisBloomFilter redisBloomFilter(RedissonClient redissonClient, RedisProperties redisProperties) {
        return new RedisBloomFilter(redissonClient, redisProperties);
    }

    /**
     * 装配分布式布隆过滤器声明式预热调度器 BloomFilterBootstrap
     *
     * @param redisBloomFilter 分布式布隆过滤器门面
     * @param redisProperties 属性配置
     * @return 布隆过滤器预热调度器
     */
    @Bean
    @ConditionalOnMissingBean(BloomFilterBootstrap.class)
    public BloomFilterBootstrap bloomFilterBootstrap(RedisBloomFilter redisBloomFilter, RedisProperties redisProperties) {
        return new BloomFilterBootstrap(redisBloomFilter, redisProperties);
    }

    /**
     * 装配工业级增强 RedisCacheManager，支持独立 TTL、空对象缓存防穿透与随机抖动防雪崩
     *
     * @param connectionFactory Redis 连接工厂
     * @param redisProperties 增强配置属性
     * @param redisValueSerializer 统一纯净值序列化器
     * @return 定制装配的 RedisCacheManager
     */
    @Bean
    @ConditionalOnMissingBean(RedisCacheManager.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory,
                                               RedisProperties redisProperties,
                                               @Qualifier("redisValueSerializer") RedisSerializer<Object> redisValueSerializer) {
        RedisProperties.CacheProperties cacheProps = redisProperties.getCache();

        // 1. 构建基准默认配置（防穿透空对象缓存、纯净 JSON 序列化、东八区时区、防精度丢失）
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisValueSerializer));

        // 2. 注入带随机抖动的 TtlFunction
        Duration defaultTtl = cacheProps.getDefaultTtl();
        Duration nullTtl = cacheProps.getNullTtl();
        Duration maxJitter = cacheProps.getMaxJitter();
        boolean jitterEnabled = cacheProps.isJitterEnabled();

        defaultConfig = defaultConfig.entryTtl(new JitterTtlFunction(defaultTtl, nullTtl, maxJitter, jitterEnabled));

        // 3. 空值缓存防穿透配置
        if (!cacheProps.isCacheNullValues()) {
            defaultConfig = defaultConfig.disableCachingNullValues();
        }

        // 4. 针对不同 cacheName 注入独立个性化 TTL 配置
        Map<String, RedisCacheConfiguration> initialCacheConfigs = new HashMap<>();
        if (!CollectionUtils.isEmpty(cacheProps.getExpires())) {
            for (Map.Entry<String, Duration> entry : cacheProps.getExpires().entrySet()) {
                String cacheName = entry.getKey();
                Duration specificTtl = entry.getValue();
                RedisCacheConfiguration specificConfig = defaultConfig.entryTtl(
                        new JitterTtlFunction(specificTtl, nullTtl, maxJitter, jitterEnabled)
                );
                initialCacheConfigs.put(cacheName, specificConfig);
            }
        }

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(initialCacheConfigs)
                .build();
    }

    /**
     * 装配全景缓存防击穿、防穿透、防雪崩安全执行模板 RedisCacheTemplate
     *
     * @param redisTemplate RedisTemplate 实例
     * @param redissonClient Redisson 客户端
     * @param redisProperties 属性配置
     * @return RedisCacheTemplate 模板实例
     */
    @Bean
    @ConditionalOnMissingBean(RedisCacheTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisCacheTemplate redisCacheTemplate(RedisTemplate<String, Object> redisTemplate,
                                                 RedissonClient redissonClient,
                                                 RedisProperties redisProperties) {
        return new RedisCacheTemplate(redisTemplate, redissonClient, RedisSerializerFactory.createSafeObjectMapper(), redisProperties);
    }

    /**
     * 装配内存+Redis 生产级两级缓存管理器 TwoLevelCacheManager
     * 具备 L1 内存极速读取与 L2 Redis 跨节点共享能力，支持跨实例 Pub/Sub 广播淘汰协同
     *
     * @param redisTemplate RedisTemplate 实例
     * @param redisProperties 属性配置
     * @param containerProvider Redis 消息监听容器提供者
     * @return TwoLevelCacheManager 实例
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(TwoLevelCacheManager.class)
    @ConditionalOnProperty(prefix = "ateng.boot.redis.two-level", name = "enabled", havingValue = "true")
    public TwoLevelCacheManager twoLevelCacheManager(RedisTemplate<String, Object> redisTemplate,
                                                     RedisProperties redisProperties,
                                                     ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        return new TwoLevelCacheManager(redisTemplate, redisProperties, containerProvider.getIfAvailable());
    }

    /**
     * 自动装配原生 RedissonClient，零侵入无缝继承 Spring Boot 官方 spring.data.redis.* 配置
     *
     * @param springRedisProperties Spring 官方数据源配置
     * @param atengProperties ateng-boot-redis 扩展属性
     * @param resourceLoader 资源加载器
     * @return 原生 RedissonClient 实例
     * @throws IOException 加载外部配置文件异常
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(org.springframework.boot.autoconfigure.data.redis.RedisProperties springRedisProperties,
                                         RedisProperties atengProperties,
                                         ResourceLoader resourceLoader) throws IOException {
        Config config;

        // 1. 若显式指定了 Redisson 外部配置文件，优先从文件加载
        String configFile = atengProperties.getRedisson().getConfig();
        if (StringUtils.hasText(configFile)) {
            Resource resource = resourceLoader.getResource(configFile);
            try (InputStream inputStream = resource.getInputStream()) {
                config = Config.fromYAML(inputStream);
            }
        } else {
            // 2. 否则自动解析 Spring 官方数据源配置进行映射
            config = createRedissonConfigFromSpringProperties(springRedisProperties, atengProperties);
        }

        // 3. 配置线程模型
        if (atengProperties.getRedisson().getThreads() > 0) {
            config.setThreads(atengProperties.getRedisson().getThreads());
        }
        if (atengProperties.getRedisson().getNettyThreads() > 0) {
            config.setNettyThreads(atengProperties.getRedisson().getNettyThreads());
        }

        // 4. 统一 Redisson Codec 为安全纯净 JSON Codec，确保与 RedisTemplate 风格自洽
        config.setCodec(new JsonJacksonCodec(RedisSerializerFactory.createSafeObjectMapper()));

        return Redisson.create(config);
    }

    /**
     * 根据 Spring 官方 RedisProperties 映射构建 Redisson Config（兼容旧签名）
     */
    Config createRedissonConfigFromSpringProperties(org.springframework.boot.autoconfigure.data.redis.RedisProperties properties) {
        return createRedissonConfigFromSpringProperties(properties, new RedisProperties());
    }

    /**
     * 根据 Spring 官方 RedisProperties 与 ateng 扩展配置映射构建 Redisson Config
     */
    Config createRedissonConfigFromSpringProperties(org.springframework.boot.autoconfigure.data.redis.RedisProperties properties,
                                                    RedisProperties atengProperties) {
        Config config = new Config();
        boolean isSsl = properties.getSsl() != null && properties.getSsl().isEnabled();
        String prefix = isSsl ? SCHEMA_SSL_PREFIX : SCHEMA_PREFIX;
        RedisProperties.RedissonProperties rp = (atengProperties != null && atengProperties.getRedisson() != null)
                ? atengProperties.getRedisson()
                : new RedisProperties.RedissonProperties();

        // 集群模式 (Cluster)
        if (properties.getCluster() != null && !CollectionUtils.isEmpty(properties.getCluster().getNodes())) {
            ClusterServersConfig clusterServersConfig = config.useClusterServers();
            List<String> nodeAddresses = new ArrayList<>();
            for (String node : properties.getCluster().getNodes()) {
                nodeAddresses.add(formatAddress(prefix, node));
            }
            clusterServersConfig.setNodeAddresses(nodeAddresses);
            if (StringUtils.hasText(properties.getPassword())) {
                clusterServersConfig.setPassword(properties.getPassword());
            }
            if (StringUtils.hasText(properties.getUsername())) {
                clusterServersConfig.setUsername(properties.getUsername());
            }
            if (properties.getTimeout() != null) {
                clusterServersConfig.setTimeout((int) properties.getTimeout().toMillis());
            }
            if (properties.getConnectTimeout() != null) {
                clusterServersConfig.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            }
            if (rp.getPingConnectionInterval() != null) {
                clusterServersConfig.setPingConnectionInterval((int) rp.getPingConnectionInterval().toMillis());
            }
            if (rp.getConnectionPoolSize() > 0) {
                clusterServersConfig.setMasterConnectionPoolSize(rp.getConnectionPoolSize());
            }
            if (rp.getConnectionMinimumIdleSize() > 0) {
                clusterServersConfig.setMasterConnectionMinimumIdleSize(rp.getConnectionMinimumIdleSize());
            }
            if (rp.getRetryAttempts() > 0) {
                clusterServersConfig.setRetryAttempts(rp.getRetryAttempts());
            }
            if (rp.getRetryInterval() != null) {
                clusterServersConfig.setRetryInterval((int) rp.getRetryInterval().toMillis());
            }
            return config;
        }

        // 哨兵模式 (Sentinel)
        if (properties.getSentinel() != null && StringUtils.hasText(properties.getSentinel().getMaster())) {
            SentinelServersConfig sentinelServersConfig = config.useSentinelServers();
            sentinelServersConfig.setMasterName(properties.getSentinel().getMaster());
            sentinelServersConfig.setDatabase(properties.getDatabase());
            for (String node : properties.getSentinel().getNodes()) {
                sentinelServersConfig.addSentinelAddress(formatAddress(prefix, node));
            }
            if (StringUtils.hasText(properties.getPassword())) {
                sentinelServersConfig.setPassword(properties.getPassword());
            }
            if (StringUtils.hasText(properties.getUsername())) {
                sentinelServersConfig.setUsername(properties.getUsername());
            }
            if (properties.getTimeout() != null) {
                sentinelServersConfig.setTimeout((int) properties.getTimeout().toMillis());
            }
            if (properties.getConnectTimeout() != null) {
                sentinelServersConfig.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            }
            if (rp.getPingConnectionInterval() != null) {
                sentinelServersConfig.setPingConnectionInterval((int) rp.getPingConnectionInterval().toMillis());
            }
            if (rp.getConnectionPoolSize() > 0) {
                sentinelServersConfig.setMasterConnectionPoolSize(rp.getConnectionPoolSize());
            }
            if (rp.getConnectionMinimumIdleSize() > 0) {
                sentinelServersConfig.setMasterConnectionMinimumIdleSize(rp.getConnectionMinimumIdleSize());
            }
            if (rp.getRetryAttempts() > 0) {
                sentinelServersConfig.setRetryAttempts(rp.getRetryAttempts());
            }
            if (rp.getRetryInterval() != null) {
                sentinelServersConfig.setRetryInterval((int) rp.getRetryInterval().toMillis());
            }
            return config;
        }

        // 单机模式 (Single Server, 默认兜底)
        SingleServerConfig singleServerConfig = config.useSingleServer();
        if (StringUtils.hasText(properties.getUrl())) {
            singleServerConfig.setAddress(properties.getUrl());
        } else {
            String host = StringUtils.hasText(properties.getHost()) ? properties.getHost() : "localhost";
            int port = properties.getPort() != 0 ? properties.getPort() : 6379;
            singleServerConfig.setAddress(formatAddress(prefix, host + ":" + port));
        }
        singleServerConfig.setDatabase(properties.getDatabase());

        if (StringUtils.hasText(properties.getPassword())) {
            singleServerConfig.setPassword(properties.getPassword());
        }
        if (StringUtils.hasText(properties.getUsername())) {
            singleServerConfig.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getClientName())) {
            singleServerConfig.setClientName(properties.getClientName());
        }
        if (properties.getTimeout() != null) {
            singleServerConfig.setTimeout((int) properties.getTimeout().toMillis());
        }
        if (properties.getConnectTimeout() != null) {
            singleServerConfig.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        }
        if (rp.getPingConnectionInterval() != null) {
            singleServerConfig.setPingConnectionInterval((int) rp.getPingConnectionInterval().toMillis());
        }
        if (rp.getConnectionPoolSize() > 0) {
            singleServerConfig.setConnectionPoolSize(rp.getConnectionPoolSize());
        }
        if (rp.getConnectionMinimumIdleSize() > 0) {
            singleServerConfig.setConnectionMinimumIdleSize(rp.getConnectionMinimumIdleSize());
        }
        if (rp.getRetryAttempts() > 0) {
            singleServerConfig.setRetryAttempts(rp.getRetryAttempts());
        }
        if (rp.getRetryInterval() != null) {
            singleServerConfig.setRetryInterval((int) rp.getRetryInterval().toMillis());
        }

        return config;
    }

    private String formatAddress(String defaultPrefix, String address) {
        if (address.startsWith(SCHEMA_PREFIX) || address.startsWith(SCHEMA_SSL_PREFIX)) {
            return address;
        }
        return defaultPrefix + address;
    }
}
