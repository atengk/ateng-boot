package io.github.atengk.boot.redis.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
import static org.mockito.Mockito.mock;

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
                    assertThat(context).doesNotHaveBean("redisMessageListenerContainer");
                    assertThat(context).doesNotHaveBean("redisMessagePublisher");
                    assertThat(context).doesNotHaveBean("channelTopicBootstrap");
                    assertThat(context).doesNotHaveBean("redisRateLimiterClient");
                    assertThat(context).doesNotHaveBean("redisAtomicUtil");
                    assertThat(context).doesNotHaveBean("redisCacheManager");
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
                    assertThat(context).hasBean("redisCacheManager");

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
}
