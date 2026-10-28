package io.github.atengk.boot.redis.cache.twolevel;

import io.github.atengk.boot.redis.autoconfigure.AtengRedisAutoConfiguration;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * TwoLevelCacheManager 单元与装配测试
 * 覆盖动态空间生成、跨节点失效广播反自重入、Spring Cache 桥接与条件自动装配
 *
 * @author Ateng
 * @since 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TwoLevelCacheManager 两级缓存管理器单元测试")
class TwoLevelCacheManagerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    private RedisProperties redisProperties;
    private TwoLevelCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        redisProperties = new RedisProperties();
        redisProperties.getTwoLevel().setEnabled(true);
        redisProperties.getTwoLevel().setTopic("ateng:cache:evict:topic");

        cacheManager = new TwoLevelCacheManager(redisTemplate, redisProperties, listenerContainer);
        cacheManager.afterPropertiesSet();
    }

    @Test
    @DisplayName("动态按需创建两级缓存空间并实现单例复用")
    void testDynamicCacheCreation() {
        Cache cache1 = cacheManager.getCache("users");
        assertThat(cache1).isNotNull();
        assertThat(cache1).isInstanceOf(TwoLevelCache.class);

        TwoLevelCache twoLevelCache = cacheManager.getTwoLevelCache("users");
        assertThat(twoLevelCache).isSameAs(cache1);

        Cache cache2 = cacheManager.getCache("users");
        assertThat(cache2).isSameAs(cache1);
    }

    @Test
    @DisplayName("广播防自重入防线：忽略自身 instanceId 发出的失效广播")
    void testHandleEvictMessageSelfInstanceIgnored() {
        TwoLevelCache cache = cacheManager.getTwoLevelCache("orders");
        DefaultTwoLevelCache defaultCache = (DefaultTwoLevelCache) cache;
        defaultCache.getL1Cache().put("ord-1", "Paid");

        // 构造来自本节点的失效消息
        CacheEvictMessage selfMessage = new CacheEvictMessage("orders", "ord-1", cacheManager.getInstanceId(), false);
        cacheManager.handleEvictMessage(selfMessage);

        // 本地 L1 缓存不应被淘汰
        assertThat(defaultCache.getL1Cache().get("ord-1")).isEqualTo("Paid");
    }

    @Test
    @DisplayName("跨节点定向失效：收到其他实例广播，精准淘汰目标 key 的本地 L1 缓存")
    void testHandleEvictMessageOtherInstanceEvicted() {
        TwoLevelCache cache = cacheManager.getTwoLevelCache("orders");
        DefaultTwoLevelCache defaultCache = (DefaultTwoLevelCache) cache;
        defaultCache.getL1Cache().put("ord-2", "Delivered");

        // 构造来自外部节点的定向失效消息
        CacheEvictMessage otherMessage = new CacheEvictMessage("orders", "ord-2", "node-bravo", false);
        cacheManager.handleEvictMessage(otherMessage);

        // 本地 L1 缓存已被淘汰
        assertThat(defaultCache.getL1Cache().get("ord-2")).isNull();
    }

    @Test
    @DisplayName("跨节点全量清理：收到其他实例广播，清空该空间下所有本地 L1 缓存")
    void testHandleEvictMessageOtherInstanceClearAll() {
        TwoLevelCache cache = cacheManager.getTwoLevelCache("orders");
        DefaultTwoLevelCache defaultCache = (DefaultTwoLevelCache) cache;
        defaultCache.getL1Cache().put("ord-3", "Shipped");
        defaultCache.getL1Cache().put("ord-4", "Completed");

        // 构造来自外部节点的全量清空消息
        CacheEvictMessage clearAllMessage = new CacheEvictMessage("orders", null, "node-bravo", true);
        cacheManager.handleEvictMessage(clearAllMessage);

        // 本地 L1 缓存全部被清空
        assertThat(defaultCache.getL1Cache().get("ord-3")).isNull();
        assertThat(defaultCache.getL1Cache().get("ord-4")).isNull();
    }

    @Test
    @DisplayName("跨节点定向失效：数值型 Key 反序列化类型漂移（Long 变 Integer）仍能精准淘汰本地 L1 缓存")
    void testHandleEvictMessageNumericKeyTypeNormalization() {
        TwoLevelCache cache = cacheManager.getTwoLevelCache("users");
        // 本地以 Long 类型写入
        cache.put(2001L, "Alice");
        assertThat(cache.get(2001L, String.class)).isEqualTo("Alice");

        // 模拟远端广播经 JSON 反序列化后 key 变为 Integer
        CacheEvictMessage remoteMessage = new CacheEvictMessage("users", 2001, "node-bravo", false);
        cacheManager.handleEvictMessage(remoteMessage);

        // 本地 L1 缓存被成功淘汰
        assertThat(cache.get(2001L)).isNull();
    }

    // ==================== 条件装配测试 ====================

    @Configuration
    static class TestMockConfiguration {
        @Bean
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        public RedisMessageListenerContainer redisMessageListenerContainer() {
            return mock(RedisMessageListenerContainer.class);
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

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRedisAutoConfiguration.class));

    @Test
    @DisplayName("当配置 ateng.boot.redis.two-level.enabled=true 时，成功装配 TwoLevelCacheManager 且成为 Primary")
    void testTwoLevelCacheManagerAutoConfiguration() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.two-level.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TwoLevelCacheManager.class);
                    // 验证作为 Primary CacheManager 生效
                    CacheManager primaryCacheManager = context.getBean(CacheManager.class);
                    assertThat(primaryCacheManager).isInstanceOf(TwoLevelCacheManager.class);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.redis.two-level.enabled=false 时，不装配 TwoLevelCacheManager")
    void testTwoLevelCacheManagerDisabled() {
        contextRunner.withUserConfiguration(TestMockConfiguration.class)
                .withPropertyValues("ateng.boot.redis.two-level.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TwoLevelCacheManager.class);
                });
    }
}
