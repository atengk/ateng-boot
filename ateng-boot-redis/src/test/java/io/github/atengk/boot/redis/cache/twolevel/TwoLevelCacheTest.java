package io.github.atengk.boot.redis.cache.twolevel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.cache.twolevel.l1.ConcurrentLruMemoryCache;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCache;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultTwoLevelCache 单元测试
 * 覆盖 L1 命中、L2 穿透读取、回填、空值防穿透、淘汰、清空、定向失效与广播通知
 *
 * @author Ateng
 * @since 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultTwoLevelCache 两级缓存单元测试")
class TwoLevelCacheTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RedisProperties redisProperties;
    private ObjectMapper objectMapper;
    private L1MemoryCache l1Cache;
    private DefaultTwoLevelCache cache;

    private static final String CACHE_NAME = "product";
    private static final String INSTANCE_ID = "node-alpha";
    private static final String TOPIC = "ateng:cache:evict:topic";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        redisProperties = new RedisProperties();
        redisProperties.getTwoLevel().setEnabled(true);
        redisProperties.getTwoLevel().setTopic(TOPIC);
        redisProperties.getTwoLevel().getL2().setDefaultTtl(Duration.ofMinutes(30));
        redisProperties.getTwoLevel().getL2().setNullTtl(Duration.ofMinutes(5));
        redisProperties.getTwoLevel().getL2().setJitterEnabled(false);

        objectMapper = RedisSerializerFactory.createSafeObjectMapper();
        l1Cache = new ConcurrentLruMemoryCache(100, Duration.ofMinutes(5));

        cache = new DefaultTwoLevelCache(
                CACHE_NAME,
                l1Cache,
                redisTemplate,
                objectMapper,
                INSTANCE_ID,
                TOPIC,
                redisProperties,
                Duration.ofMinutes(30),
                true,
                Duration.ofMinutes(5)
        );
    }

    @Test
    @DisplayName("L1 命中：直接从本地内存返回，完全不触发 Redis IO")
    void testL1Hit() {
        l1Cache.put("1001", "Laptop");

        Cache.ValueWrapper wrapper = cache.get("1001");
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get()).isEqualTo("Laptop");

        // 验证没有进行任何 Redis 查询
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("L1 未命中但 L2 命中：查询 Redis，回填本地 L1，后续调用直接命中 L1")
    void testL1MissL2HitAndBackfill() {
        when(valueOperations.get("product:1001")).thenReturn("Laptop");

        // 第一次查询：L1 miss，L2 hit
        Cache.ValueWrapper wrapper1 = cache.get("1001");
        assertThat(wrapper1).isNotNull();
        assertThat(wrapper1.get()).isEqualTo("Laptop");
        verify(valueOperations, times(1)).get("product:1001");

        // 验证已自动回填 L1
        assertThat(l1Cache.get("1001")).isEqualTo("Laptop");

        // 第二次查询：直接命中 L1，不再触发 Redis
        Cache.ValueWrapper wrapper2 = cache.get("1001");
        assertThat(wrapper2).isNotNull();
        assertThat(wrapper2.get()).isEqualTo("Laptop");
        verify(valueOperations, times(1)).get("product:1001");
    }

    @Test
    @DisplayName("两级均未命中：返回 null")
    void testBothMiss() {
        when(valueOperations.get("product:9999")).thenReturn(null);

        Cache.ValueWrapper wrapper = cache.get("9999");
        assertThat(wrapper).isNull();
        verify(valueOperations, times(1)).get("product:9999");
    }

    @Test
    @DisplayName("类型转换：get(key, Class<T>) 正确转换类型")
    void testGetWithType() {
        l1Cache.put("count", 42);

        Integer count = cache.get("count", Integer.class);
        assertThat(count).isEqualTo(42);
    }

    @Test
    @DisplayName("Callable 加载：双重检查并回填两级缓存")
    void testGetWithCallableLoader() {
        String result = cache.get("1002", () -> "Smartphone");
        assertThat(result).isEqualTo("Smartphone");

        // 验证回填了 L1 和 L2
        assertThat(l1Cache.get("1002")).isEqualTo("Smartphone");
        verify(valueOperations).set(eq("product:1002"), eq("Smartphone"), any(Duration.class));
        verify(redisTemplate).convertAndSend(eq(TOPIC), any(CacheEvictMessage.class));
    }

    @Test
    @DisplayName("写入缓存：更新 L1、L2 并广播跨节点失效通知")
    void testPutAndBroadcast() {
        cache.put("1003", "Tablet");

        // 1. 验证写入 L1
        assertThat(l1Cache.get("1003")).isEqualTo("Tablet");

        // 2. 验证写入 L2
        verify(valueOperations).set(eq("product:1003"), eq("Tablet"), any(Duration.class));

        // 3. 验证广播失效消息
        ArgumentCaptor<CacheEvictMessage> captor = ArgumentCaptor.forClass(CacheEvictMessage.class);
        verify(redisTemplate).convertAndSend(eq(TOPIC), captor.capture());

        CacheEvictMessage message = captor.getValue();
        assertThat(message.getCacheName()).isEqualTo(CACHE_NAME);
        assertThat(message.getKey()).isEqualTo("1003");
        assertThat(message.getInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(message.isClearAll()).isFalse();
    }

    @Test
    @DisplayName("防穿透：写入 null 值时存入短 TTL 哨兵标记")
    void testPutNullValueSentinel() {
        cache.put("emptyKey", null);

        // 1. L1 存入哨兵
        assertThat(l1Cache.get("emptyKey")).isEqualTo(DefaultTwoLevelCache.NULL_SENTINEL);

        // 2. L2 存入哨兵与 short nullTtl
        verify(valueOperations).set(eq("product:emptyKey"), eq(DefaultTwoLevelCache.NULL_SENTINEL), eq(Duration.ofMinutes(5)));

        // 3. 读取时包装为 null
        Cache.ValueWrapper wrapper = cache.get("emptyKey");
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get()).isNull();
    }

    @Test
    @DisplayName("淘汰操作：清除本地 L1、删除 L2 键并广播失效通知")
    void testEvict() {
        l1Cache.put("1004", "Watch");

        cache.evict("1004");

        // 1. L1 已被移除
        assertThat(l1Cache.get("1004")).isNull();

        // 2. L2 键被删除
        verify(redisTemplate).delete("product:1004");

        // 3. 发送广播
        verify(redisTemplate).convertAndSend(eq(TOPIC), any(CacheEvictMessage.class));
    }

    @Test
    @DisplayName("本地定向失效：仅淘汰本地 L1，绝不删除 Redis 且不广播")
    void testEvictLocal() {
        l1Cache.put("1005", "Camera");

        cache.evictLocal("1005");

        // L1 已清空
        assertThat(l1Cache.get("1005")).isNull();

        // 绝不删除 Redis，绝不触发广播
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).convertAndSend(anyString(), any());
    }

    @Test
    @DisplayName("本地定向清空：仅清空本地 L1，绝不触发 Redis 删除与广播")
    void testClearLocal() {
        l1Cache.put("1006", "Headphones");
        l1Cache.put("1007", "Earphones");

        cache.clearLocal();

        assertThat(l1Cache.get("1006")).isNull();
        assertThat(l1Cache.get("1007")).isNull();

        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).convertAndSend(anyString(), any());
    }

    @Test
    @DisplayName("全局 KeyPrefix 隔离契约：自动拼接全局前缀")
    void testKeyPrefixIsolation() {
        redisProperties.setKeyPrefix("myapp:");

        String builtKey = cache.buildRedisKey("1008");
        assertThat(builtKey).isEqualTo("myapp:product:1008");
    }
}
