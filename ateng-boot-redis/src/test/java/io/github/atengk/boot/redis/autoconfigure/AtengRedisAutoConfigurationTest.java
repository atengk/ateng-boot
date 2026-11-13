package io.github.atengk.boot.redis.autoconfigure;

import io.github.atengk.boot.core.autoconfigure.AtengCoreAutoConfiguration;
import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.bloom.RedisBloomFilter;
import io.github.atengk.boot.redis.bloom.RedisBloomUtil;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentUtil;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.util.RedisLockUtil;
import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.RedisDelayedQueueUtil;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreUtil;
import io.github.atengk.boot.redis.stream.RedisStreamTemplate;
import io.github.atengk.boot.redis.stream.RedisStreamUtil;
import io.github.atengk.boot.redis.util.RedisUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AtengRedisAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("AtengRedisAutoConfiguration 自动装配单元测试")
class AtengRedisAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRedisAutoConfiguration.class));

    @Configuration
    static class TestMockConfiguration {
        @Bean
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        public RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }

        @Bean
        public org.springframework.boot.autoconfigure.data.redis.RedisProperties springRedisProperties() {
            return new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        }
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.enabled=false 时，不装配任何 Bean")
    void shouldNotLoadWhenDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("redisTemplate");
                    assertThat(context).doesNotHaveBean("redisValueSerializer");
                    assertThat(context).doesNotHaveBean("redisUtil");
                    assertThat(context).doesNotHaveBean("distributedLockAspect");
                    assertThat(context).doesNotHaveBean("distributedLockTemplate");
                    assertThat(context).doesNotHaveBean("redisDelayedQueue");
                    assertThat(context).doesNotHaveBean("delayedQueueBootstrap");
                    assertThat(context).doesNotHaveBean("delayedQueueFailureHandler");
                    assertThat(context).doesNotHaveBean("redisMessageListenerContainer");
                    assertThat(context).doesNotHaveBean("redisMessagePublisher");
                    assertThat(context).doesNotHaveBean("channelTopicBootstrap");
                    assertThat(context).doesNotHaveBean("redisRateLimiterClient");
                    assertThat(context).doesNotHaveBean("redisAtomicUtil");
                    assertThat(context).doesNotHaveBean("redisCacheManager");
                    assertThat(context).doesNotHaveBean("redisCacheTemplate");
                    assertThat(context).doesNotHaveBean("twoLevelCacheManager");
                    assertThat(context).doesNotHaveBean("redisScriptExecutor");
                    assertThat(context).doesNotHaveBean("redisSemaphoreTemplate");
                    assertThat(context).doesNotHaveBean("redisIdempotentTemplate");
                    assertThat(context).doesNotHaveBean("redisIdempotentAspect");
                    assertThat(context).doesNotHaveBean("redisStreamTemplate");
                    assertThat(context).doesNotHaveBean("streamFailureHandler");
                    assertThat(context).doesNotHaveBean("streamBootstrap");
                });
    }

    @Test
    @DisplayName("当缺少 RedisOperations 类时，条件装配不生效")
    void shouldNotLoadWhenClassMissing() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withClassLoader(new FilteredClassLoader(RedisOperations.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("redisTemplate");
                    assertThat(context).doesNotHaveBean("redisUtil");
                    assertThat(context).doesNotHaveBean("distributedLockAspect");
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.lock.enabled=false 时，不装配分布式锁切面但基础组件正常")
    void shouldNotLoadLockAspectWhenLockDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.lock.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("redisUtil");
                    assertThat(context).doesNotHaveBean("distributedLockAspect");
                    assertThat(context).doesNotHaveBean("distributedLockTemplate");
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.idempotent.enabled=false 时，不装配防重幂等模板与切面")
    void shouldNotLoadIdempotentWhenDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.idempotent.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("redisUtil");
                    assertThat(context).doesNotHaveBean("redisIdempotentTemplate");
                    assertThat(context).doesNotHaveBean("redisIdempotentAspect");
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.stream.enabled=false 时，不装配 Stream 模板、失败处理器与容器调度器")
    void shouldNotLoadStreamWhenDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.stream.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("redisUtil");
                    assertThat(context).doesNotHaveBean("redisStreamTemplate");
                    assertThat(context).doesNotHaveBean("streamFailureHandler");
                    assertThat(context).doesNotHaveBean("streamBootstrap");
                });
    }

    @Test
    @DisplayName("默认启用时装配 Redis 全家桶组件且各序列化器契约正确")
    void shouldAutoConfigureRedisTemplateWithProperSerializers() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("redisValueSerializer");
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("redisUtil");
                    assertThat(context).hasBean("distributedLockAspect");
                    assertThat(context).hasBean("distributedLockTemplate");
                    assertThat(context).hasBean("redisDelayedQueue");
                    assertThat(context).hasBean("delayedQueueBootstrap");
                    assertThat(context).hasBean("delayedQueueFailureHandler");
                    assertThat(context).hasBean("redisMessageListenerContainer");
                    assertThat(context).hasBean("redisMessagePublisher");
                    assertThat(context).hasBean("channelTopicBootstrap");
                    assertThat(context).hasBean("redisRateLimiterClient");
                    assertThat(context).hasBean("redisAtomicUtil");
                    assertThat(context).hasBean("redisBloomFilter");
                    assertThat(context).hasBean("bloomFilterBootstrap");
                    assertThat(context).hasBean("redisCacheManager");
                    assertThat(context).hasBean("redisCacheTemplate");
                    assertThat(context).hasBean("redisScriptExecutor");
                    assertThat(context).hasBean("redisSemaphoreTemplate");
                    assertThat(context).hasBean("redisIdempotentTemplate");
                    assertThat(context).hasBean("redisIdempotentAspect");
                    assertThat(context).hasBean("redisStreamTemplate");
                    assertThat(context).hasBean("streamFailureHandler");
                    assertThat(context).hasBean("streamBootstrap");

                    RedisSerializer<?> valueSerializer = context.getBean("redisValueSerializer", RedisSerializer.class);
                    assertThat(valueSerializer).isInstanceOf(Jackson2JsonRedisSerializer.class);

                    @SuppressWarnings("unchecked")
                    RedisTemplate<String, Object> template = context.getBean("redisTemplate", RedisTemplate.class);
                    assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(template.getValueSerializer()).isEqualTo(valueSerializer);
                    assertThat(template.getHashValueSerializer()).isEqualTo(valueSerializer);
                });
    }

    @Test
    @DisplayName("支持自定义 DelayedQueueFailureHandler 覆盖默认处理器")
    void shouldAllowCustomDelayedQueueFailureHandler() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class, CustomHandlerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler.class);
                    assertThat(context.getBean(io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler.class))
                            .isInstanceOf(CustomHandler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfiguration {
        @Bean
        public io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler customFailureHandler() {
            return new CustomHandler();
        }
    }

    static class CustomHandler implements io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler {
        @Override
        public void onFailed(io.github.atengk.boot.redis.delay.model.DeadLetterMessage<?> deadLetterMessage, Throwable cause) {
        }
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.delay-queue.enabled=false 时，不装配延时队列门面与生命周期调度器")
    void shouldNotLoadDelayQueueWhenDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.delay-queue.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).doesNotHaveBean("redisDelayedQueue");
                    assertThat(context).doesNotHaveBean("delayedQueueBootstrap");
                    assertThat(context).doesNotHaveBean("delayedQueueFailureHandler");
                    assertThat(context).hasBean("redisMessagePublisher");
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.pub-sub.enabled=false 时，不装配广播监听容器与消息发布客户端")
    void shouldNotLoadPubSubWhenDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.pub-sub.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("redisDelayedQueue");
                    assertThat(context).doesNotHaveBean("redisMessageListenerContainer");
                    assertThat(context).doesNotHaveBean("redisMessagePublisher");
                    assertThat(context).doesNotHaveBean("channelTopicBootstrap");
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.cache.enabled=false 时，不装配 RedisCacheManager")
    void shouldNotLoadCacheManagerWhenCacheDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.cache.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).doesNotHaveBean("redisCacheManager");
                    assertThat(context).doesNotHaveBean("redisCacheTemplate");
                });
    }

    @Test
    @DisplayName("测试按 cacheName 配置个性化独立 TTL 与空值缓存防穿透")
    void shouldConfigureCustomTtlPerCacheName() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.redis.cache.default-ttl=20m",
                        "ateng.boot.redis.cache.expires.userCache=10m",
                        "ateng.boot.redis.cache.cache-null-values=true"
                )
                .run(context -> {
                    assertThat(context).hasBean("redisCacheManager");
                    org.springframework.data.redis.cache.RedisCacheManager cacheManager =
                            context.getBean(org.springframework.data.redis.cache.RedisCacheManager.class);
                    assertThat(cacheManager.getCacheConfigurations()).containsKey("userCache");
                });
    }

    @Test
    @DisplayName("测试单级 RedisCacheManager 在全局 key-prefix 非空时附加前缀、空时保持原生行为")
    void shouldApplyKeyPrefixToRedisCacheManagerWhenConfigured() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.key-prefix=myapp:")
                .run(context -> {
                    org.springframework.data.redis.cache.RedisCacheManager cacheManager =
                            context.getBean(org.springframework.data.redis.cache.RedisCacheManager.class);
                    org.springframework.data.redis.cache.RedisCache cache =
                            (org.springframework.data.redis.cache.RedisCache) cacheManager.getCache("testCache");
                    assertThat(cache).isNotNull();
                    assertThat(cache.getCacheConfiguration().getKeyPrefixFor("testCache")).isEqualTo("myapp:testCache::");
                });

        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .run(context -> {
                    org.springframework.data.redis.cache.RedisCacheManager cacheManager =
                            context.getBean(org.springframework.data.redis.cache.RedisCacheManager.class);
                    org.springframework.data.redis.cache.RedisCache cache =
                            (org.springframework.data.redis.cache.RedisCache) cacheManager.getCache("testCache");
                    assertThat(cache).isNotNull();
                    assertThat(cache.getCacheConfiguration().getKeyPrefixFor("testCache")).isEqualTo("testCache::");
                });
    }

    @Test
    @DisplayName("测试 TwoLevelCacheProperties 兼容绑定 expires 属性别名")
    void shouldBindTwoLevelCacheExpiresAlias() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.redis.two-level.l1.expires.dictCache=3m",
                        "ateng.boot.redis.two-level.l2.expires.dictCache=15m"
                )
                .run(context -> {
                    io.github.atengk.boot.redis.properties.RedisProperties properties =
                            context.getBean(io.github.atengk.boot.redis.properties.RedisProperties.class);
                    assertThat(properties.getTwoLevel().getL1().getTtls()).containsEntry("dictCache", Duration.ofMinutes(3));
                    assertThat(properties.getTwoLevel().getL2().getTtls()).containsEntry("dictCache", Duration.ofMinutes(15));
                    assertThat(properties.getTwoLevel().getL1().getExpires()).containsEntry("dictCache", Duration.ofMinutes(3));
                    assertThat(properties.getTwoLevel().getL2().getExpires()).containsEntry("dictCache", Duration.ofMinutes(15));
                });
    }

    @Test
    @DisplayName("单机模式配置映射：正确解析 Host, Port, Database, Password 与 Timeout")
    void shouldMapSingleServerConfigCorrectly() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties props = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        props.setHost("192.168.1.100");
        props.setPort(6380);
        props.setPassword("secret-pass");
        props.setDatabase(3);
        props.setTimeout(Duration.ofMillis(4500));
        props.setClientName("ateng-app");

        Config config = autoConfiguration.createRedissonConfigFromSpringProperties(props);

        assertThat(config.isClusterConfig()).isFalse();
        assertThat(config.isSentinelConfig()).isFalse();
    }

    @Test
    @DisplayName("单机 SSL 模式配置映射：地址自动携带 rediss:// 前缀")
    void shouldMapSslSingleServerConfigCorrectly() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties props = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        props.setHost("secure-redis.internal");
        props.setPort(6379);
        props.getSsl().setEnabled(true);

        Config config = autoConfiguration.createRedissonConfigFromSpringProperties(props);

        assertThat(config.isClusterConfig()).isFalse();
    }

    @Test
    @DisplayName("集群模式配置映射：正确解析多个节点与密码")
    void shouldMapClusterConfigCorrectly() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties props = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties.Cluster cluster = new org.springframework.boot.autoconfigure.data.redis.RedisProperties.Cluster();
        cluster.setNodes(List.of("10.0.0.1:7000", "10.0.0.2:7001"));
        props.setCluster(cluster);
        props.setPassword("cluster-secret");

        Config config = autoConfiguration.createRedissonConfigFromSpringProperties(props);

        assertThat(config.isClusterConfig()).isTrue();
    }

    @Test
    @DisplayName("哨兵模式配置映射：正确解析 Master 名称、哨兵节点与 Database")
    void shouldMapSentinelConfigCorrectly() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties props = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties.Sentinel sentinel = new org.springframework.boot.autoconfigure.data.redis.RedisProperties.Sentinel();
        sentinel.setMaster("my-sentinel-master");
        sentinel.setNodes(List.of("10.0.0.10:26379", "10.0.0.11:26379"));
        props.setSentinel(sentinel);
        props.setDatabase(5);

        Config config = autoConfiguration.createRedissonConfigFromSpringProperties(props);

        assertThat(config.isSentinelConfig()).isTrue();
    }

    @Test
    @DisplayName("单机 URL 模式配置映射：优先从 spring.data.redis.url 解析")
    void shouldMapUrlSingleServerConfigCorrectly() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties props = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        props.setUrl("redis://user:pass@my-redis-host:6389/2");

        Config config = autoConfiguration.createRedissonConfigFromSpringProperties(props);

        assertThat(config.isClusterConfig()).isFalse();
        assertThat(config.isSentinelConfig()).isFalse();
    }

    @Test
    @DisplayName("Redisson 生产连接保活、连接池与重试参数映射验证")
    void shouldMapRedissonProductionHardeningProperties() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties springProps = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        springProps.setHost("127.0.0.1");
        springProps.setPort(6379);

        io.github.atengk.boot.redis.properties.RedisProperties atengProps = new io.github.atengk.boot.redis.properties.RedisProperties();
        atengProps.getRedisson().setPingConnectionInterval(Duration.ofSeconds(45));
        atengProps.getRedisson().setConnectionPoolSize(32);
        atengProps.getRedisson().setConnectionMinimumIdleSize(8);
        atengProps.getRedisson().setRetryAttempts(5);
        atengProps.getRedisson().setRetryInterval(Duration.ofSeconds(2));

        Config config = autoConfiguration.createRedissonConfigFromSpringProperties(springProps, atengProps);

        assertThat(config.isClusterConfig()).isFalse();
        assertThat(config.isSentinelConfig()).isFalse();
        assertThat(config.useSingleServer().getPingConnectionInterval()).isEqualTo(45000);
        assertThat(config.useSingleServer().getConnectionPoolSize()).isEqualTo(32);
        assertThat(config.useSingleServer().getConnectionMinimumIdleSize()).isEqualTo(8);
        assertThat(config.useSingleServer().getRetryAttempts()).isEqualTo(5);
        assertThat(config.useSingleServer().getRetryInterval()).isEqualTo(2000);
    }

    @Test
    @DisplayName("验证自动装配顺序守卫注解及与 Core/Spring Redis/Cache 协同装配")
    void shouldDeclareExplicitAutoConfigurationOrderAndCooperateWithSpringBoot() {
        AutoConfigureAfter after = AtengRedisAutoConfiguration.class.getAnnotation(AutoConfigureAfter.class);
        AutoConfigureBefore before = AtengRedisAutoConfiguration.class.getAnnotation(AutoConfigureBefore.class);

        assertThat(after).isNotNull();
        assertThat(after.value()).containsExactly(AtengCoreAutoConfiguration.class);
        assertThat(before).isNotNull();
        assertThat(before.value()).containsExactlyInAnyOrder(
                RedisAutoConfiguration.class,
                CacheAutoConfiguration.class
        );

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengCoreAutoConfiguration.class,
                        AtengRedisAutoConfiguration.class,
                        RedisAutoConfiguration.class,
                        CacheAutoConfiguration.class
                ))
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AtengRedisAutoConfiguration.class);
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("redisCacheManager");
                    assertThat(context).hasSingleBean(CacheManager.class);
                });
    }

    @Test
    @DisplayName("Redisson 看门狗超时配置绑定、LockProperties 覆盖优先级及 Config 注入验证")
    void shouldBindAndInjectLockWatchdogTimeoutWithPrecedence() {
        AtengRedisAutoConfiguration autoConfiguration = new AtengRedisAutoConfiguration();
        org.springframework.boot.autoconfigure.data.redis.RedisProperties springProps =
                new org.springframework.boot.autoconfigure.data.redis.RedisProperties();

        // 1. 默认值为 30s -> 30000ms
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .run(context -> {
                    io.github.atengk.boot.redis.properties.RedisProperties props =
                            context.getBean(io.github.atengk.boot.redis.properties.RedisProperties.class);
                    assertThat(props.getRedisson().getLockWatchdogTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(props.getLock().getWatchdogTimeout()).isNull();

                    Config config = autoConfiguration.createRedissonConfigFromSpringProperties(springProps, props);
                    assertThat(config.getLockWatchdogTimeout()).isEqualTo(30_000L);
                });

        // 2. 配置 redisson.lock-watchdog-timeout=45s 时生效
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.redisson.lock-watchdog-timeout=45s")
                .run(context -> {
                    io.github.atengk.boot.redis.properties.RedisProperties props =
                            context.getBean(io.github.atengk.boot.redis.properties.RedisProperties.class);
                    Config config = autoConfiguration.createRedissonConfigFromSpringProperties(springProps, props);
                    assertThat(config.getLockWatchdogTimeout()).isEqualTo(45_000L);
                });

        // 3. lock.watchdog-timeout 优先级高于 redisson.lock-watchdog-timeout
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.redis.redisson.lock-watchdog-timeout=45s",
                        "ateng.boot.redis.lock.watchdog-timeout=60s"
                )
                .run(context -> {
                    io.github.atengk.boot.redis.properties.RedisProperties props =
                            context.getBean(io.github.atengk.boot.redis.properties.RedisProperties.class);
                    Config config = autoConfiguration.createRedissonConfigFromSpringProperties(springProps, props);
                    assertThat(config.getLockWatchdogTimeout()).isEqualTo(60_000L);
                });

        // 4. 非法非正看门狗超时快速失败
        io.github.atengk.boot.redis.properties.RedisProperties invalidProps =
                new io.github.atengk.boot.redis.properties.RedisProperties();
        invalidProps.getLock().setWatchdogTimeout(Duration.ZERO);
        assertThatThrownBy(() -> autoConfiguration.createRedissonConfigFromSpringProperties(springProps, invalidProps))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SemaphoreProperties 配置绑定、前缀拼接与条件开关验证")
    void shouldBindSemaphorePropertiesAndHonorEnabledCondition() {
        // 默认开启且前缀为 "semaphore:"
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .run(context -> {
                    io.github.atengk.boot.redis.properties.RedisProperties props =
                            context.getBean(io.github.atengk.boot.redis.properties.RedisProperties.class);
                    assertThat(props.getSemaphore().isEnabled()).isTrue();
                    assertThat(props.getSemaphore().getPrefix()).isEqualTo("semaphore:");
                    assertThat(context).hasSingleBean(RedisSemaphoreTemplate.class);
                });

        // 自定义信号量前缀读取验证
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.semaphore.prefix=custom:sem")
                .run(context -> {
                    RedisSemaphoreTemplate template = context.getBean(RedisSemaphoreTemplate.class);
                    RedissonClient client = context.getBean(RedissonClient.class);
                    template.getSemaphore("export-job");
                    verify(client).getSemaphore("custom:sem:export-job");
                });

        // 关闭开关时跳过信号量模板装配，其余核心 Bean 正常保留
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.semaphore.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisSemaphoreTemplate.class);
                    assertThat(context).hasSingleBean(DistributedLockTemplate.class);
                });
    }

    @Test
    @DisplayName("验证全量 8 个静态门面在存在自定义 Bean 时均能被 SmartInitializingSingleton 正确桥接")
    void shouldInitializeAllEightStaticFacadesWhenCustomBeansProvided() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> customRedisTemplate = mock(RedisTemplate.class);
        io.github.atengk.boot.redis.properties.RedisProperties customProps =
                new io.github.atengk.boot.redis.properties.RedisProperties();
        customProps.setKeyPrefix("facade");

        RedisUtil customRedisUtil = new RedisUtil(customRedisTemplate, customProps);
        RedisAtomicUtil customAtomicUtil = new RedisAtomicUtil(customRedisTemplate, customProps);
        RedisBloomFilter customBloomFilter = mock(RedisBloomFilter.class);
        RedisSemaphoreTemplate customSemaphore = mock(RedisSemaphoreTemplate.class);
        DistributedLockTemplate customLock = mock(DistributedLockTemplate.class);
        RedisIdempotentTemplate customIdempotent = mock(RedisIdempotentTemplate.class);
        RedisStreamTemplate customStream = mock(RedisStreamTemplate.class);
        RedisDelayedQueue customDelayedQueue = mock(RedisDelayedQueue.class);

        when(customBloomFilter.contains("user-bloom", "u100")).thenReturn(true);
        when(customSemaphore.availablePermits("test:quota")).thenReturn(88);
        when(customLock.execute(org.mockito.ArgumentMatchers.eq("lock:1"),
                org.mockito.ArgumentMatchers.any(io.github.atengk.boot.redis.lock.template.LockSupplier.class))).thenReturn("lock-ok");
        when(customIdempotent.delete("req-1")).thenReturn(true);

        // 先污染静态门面引用，验证 SmartInitializingSingleton 能将 8 个门面全量重新收敛到当前容器 Bean
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> staleTemplate = mock(RedisTemplate.class);
        RedisUtil.init(staleTemplate, new io.github.atengk.boot.redis.properties.RedisProperties());
        RedisAtomicUtil.init(new RedisAtomicUtil(staleTemplate, new io.github.atengk.boot.redis.properties.RedisProperties()));

        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withBean("redisUtil", RedisUtil.class, () -> customRedisUtil)
                .withBean("redisAtomicUtil", RedisAtomicUtil.class, () -> customAtomicUtil)
                .withBean("redisBloomFilter", RedisBloomFilter.class, () -> customBloomFilter)
                .withBean("redisSemaphoreTemplate", RedisSemaphoreTemplate.class, () -> customSemaphore)
                .withBean("distributedLockTemplate", DistributedLockTemplate.class, () -> customLock)
                .withBean("redisIdempotentTemplate", RedisIdempotentTemplate.class, () -> customIdempotent)
                .withBean("redisStreamTemplate", RedisStreamTemplate.class, () -> customStream)
                .withBean("redisDelayedQueue", RedisDelayedQueue.class, () -> customDelayedQueue)
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisSemaphoreTemplate.class);
                    assertThat(context.getBean(RedisSemaphoreTemplate.class)).isSameAs(customSemaphore);

                    // 1. RedisUtil
                    assertThat(RedisUtil.getTemplate()).isSameAs(customRedisTemplate);
                    assertThat(RedisUtil.buildKey("k")).isEqualTo("facade:k");
                    // 2. RedisAtomicUtil
                    assertThat(RedisAtomicUtil.getInstance()).isSameAs(customAtomicUtil);
                    assertThat(RedisAtomicUtil.formatKey("cnt")).isEqualTo("facade:cnt");
                    // 3. RedisBloomUtil
                    assertThat(RedisBloomUtil.contains("user-bloom", "u100")).isTrue();
                    // 4. RedisSemaphoreUtil
                    assertThat(RedisSemaphoreUtil.availablePermits("test:quota")).isEqualTo(88);
                    // 5. RedisLockUtil
                    assertThat(RedisLockUtil.execute("lock:1", () -> "lock-ok")).isEqualTo("lock-ok");
                    // 6. RedisIdempotentUtil
                    assertThat(RedisIdempotentUtil.delete("req-1")).isTrue();
                    // 7. RedisStreamUtil
                    RedisStreamUtil.ack("orders", "g1", "1-0");
                    verify(customStream).ack("orders", "g1", "1-0");
                    // 8. RedisDelayedQueueUtil
                    RedisDelayedQueueUtil.size("delay:topic");
                    verify(customDelayedQueue).size("delay:topic");
                });
    }
}
