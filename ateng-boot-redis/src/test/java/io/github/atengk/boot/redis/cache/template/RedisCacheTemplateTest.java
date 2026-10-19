package io.github.atengk.boot.redis.cache.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisCacheTemplate 全景防卫单元测试
 * 覆盖防击穿、防穿透、防雪崩、争锁超时降级与批量 MGET 防 N+1 穿透
 *
 * @author Ateng
 * @since 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCacheTemplate 全景防卫单元测试")
class RedisCacheTemplateTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private ObjectMapper objectMapper;
    private RedisProperties redisProperties;
    private RedisCacheTemplate cacheTemplate;

    /**
     * 单测测试内部 POJO（手写 Getter/Setter 规避 Lombok 注解处理器兼容隐患）
     */
    static class UserDemo {
        private Long id;
        private String name;

        public UserDemo() {
        }

        public UserDemo(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = RedisSerializerFactory.createSafeObjectMapper();
        redisProperties = new RedisProperties();
        redisProperties.setKeyPrefix("app:");
        redisProperties.getCache().setDefaultTtl(Duration.ofMinutes(10));
        redisProperties.getCache().setNullTtl(Duration.ofMinutes(2));
        redisProperties.getCache().setJitterEnabled(true);
        redisProperties.getCache().setMaxJitter(Duration.ofSeconds(30));
        redisProperties.getCache().setLockWaitTime(Duration.ofSeconds(2));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(rLock);

        cacheTemplate = new RedisCacheTemplate(redisTemplate, redissonClient, objectMapper, redisProperties);
    }

    @Test
    @DisplayName("1. 参数校验与前置防卫测试")
    void testValidationGuard() {
        assertThatThrownBy(() -> cacheTemplate.getOrLoad("", UserDemo.class, key -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cacheTemplate.getOrLoad("user:1", (Class<UserDemo>) null, key -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cacheTemplate.getOrLoad("user:1", UserDemo.class, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. 缓存命中直接返回，不触发加锁与查库 (Fast Path)")
    void testCacheHitDirectReturn() {
        UserDemo cachedUser = new UserDemo(1001L, "Alice");
        when(valueOperations.get("app:user:1001")).thenReturn(cachedUser);

        AtomicInteger loaderCalls = new AtomicInteger(0);
        UserDemo result = cacheTemplate.getOrLoad("user:1001", UserDemo.class, key -> {
            loaderCalls.incrementAndGet();
            return new UserDemo(1001L, "FromDb");
        });

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1001L);
        assertThat(result.getName()).isEqualTo("Alice");
        assertThat(loaderCalls.get()).isEqualTo(0);

        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("3. 命中空值哨兵直接返回 null，防穿透不查库 (Cache Hit Null Sentinel)")
    void testCacheHitNullSentinelDirectReturn() {
        when(valueOperations.get("app:user:non_exist")).thenReturn(RedisCacheTemplate.NULL_SENTINEL);

        AtomicInteger loaderCalls = new AtomicInteger(0);
        UserDemo result = cacheTemplate.getOrLoad("user:non_exist", UserDemo.class, key -> {
            loaderCalls.incrementAndGet();
            return new UserDemo(9999L, "FromDb");
        });

        assertThat(result).isNull();
        assertThat(loaderCalls.get()).isEqualTo(0);
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("4. 缓存未命中，争锁查库并回写缓存 (Cache Miss & Load)")
    void testCacheMissAndLoad() throws Exception {
        when(valueOperations.get("app:user:1002")).thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        UserDemo dbUser = new UserDemo(1002L, "Bob");
        UserDemo result = cacheTemplate.getOrLoad("user:1002", UserDemo.class, key -> dbUser);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1002L);
        assertThat(result.getName()).isEqualTo("Bob");

        // 验证争锁与释放
        verify(rLock).tryLock(eq(2000L), eq(-1L), eq(TimeUnit.MILLISECONDS));
        verify(rLock).unlock();

        // 验证写回 Redis（带 TTL）
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("app:user:1002"), eq(dbUser), durationCaptor.capture());

        Duration savedTtl = durationCaptor.getValue();
        // 基准 10m + 抖动 (0~30s)
        assertThat(savedTtl.toSeconds()).isBetween(600L, 631L);
    }

    @Test
    @DisplayName("5. DCL 双重检查命中，避免并发重复查库 (DCL Hit)")
    void testDoubleCheckedLockingHit() throws Exception {
        // 第一次检查未命中，获取锁后第二次检查发现已有其他线程写入
        when(valueOperations.get("app:user:1003"))
                .thenReturn(null)
                .thenReturn(new UserDemo(1003L, "Charlie"));

        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        AtomicInteger loaderCalls = new AtomicInteger(0);
        UserDemo result = cacheTemplate.getOrLoad("user:1003", UserDemo.class, key -> {
            loaderCalls.incrementAndGet();
            return new UserDemo(1003L, "FromDb");
        });

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Charlie");
        assertThat(loaderCalls.get()).isEqualTo(0); // loader 未调用

        verify(rLock).unlock();
    }

    @Test
    @DisplayName("6. 查库结果为空，写入短 TTL 空值哨兵防穿透 (Anti-Penetration Null Cache)")
    void testCacheNullValueWhenDbReturnsNull() throws Exception {
        when(valueOperations.get("app:user:not_found")).thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        UserDemo result = cacheTemplate.getOrLoad("user:not_found", UserDemo.class, key -> null);

        assertThat(result).isNull();

        // 验证写回了 NULL_SENTINEL，TTL 采用 nullTtl (2m)
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("app:user:not_found"), eq(RedisCacheTemplate.NULL_SENTINEL), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("7. 争抢分布式锁超时，平滑穿透降级查库 (Resilience Pass-Through)")
    void testLockTimeoutResilience() throws Exception {
        when(valueOperations.get("app:user:hot_key")).thenReturn(null);
        // 模拟争锁超时
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        UserDemo fallbackUser = new UserDemo(8888L, "FallbackUser");
        UserDemo result = cacheTemplate.getOrLoad("user:hot_key", UserDemo.class, key -> fallbackUser);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("FallbackUser");

        // 确保不会抛出异常阻断业务，且未调用 unlock
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("8. 复杂泛型 TypeReference 读取与类型安全转换")
    void testGenericTypeReferenceSupport() {
        List<UserDemo> cachedList = Arrays.asList(new UserDemo(1L, "U1"), new UserDemo(2L, "U2"));
        when(valueOperations.get("app:users:all")).thenReturn(cachedList);

        List<UserDemo> result = cacheTemplate.getOrLoad("users:all", new TypeReference<List<UserDemo>>() {}, key -> null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("U1");
    }

    @Test
    @DisplayName("9. 批量 batchGetOrLoad 完整流程：部分命中 + 批量查库回源 + Pipeline 批量回写")
    void testBatchGetOrLoadFlow() throws Exception {
        List<String> keys = Arrays.asList("item:1", "item:2", "item:3");

        // 模拟 MGET：item:1 命中，item:2 和 item:3 未命中
        when(valueOperations.multiGet(Arrays.asList("app:item:1", "app:item:2", "app:item:3")))
                .thenReturn(Arrays.asList("Val1", null, null));

        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // DCL 第二次 MGET 仍然未命中
        when(valueOperations.multiGet(Arrays.asList("app:item:2", "app:item:3")))
                .thenReturn(Arrays.asList(null, null));

        // 模拟 Pipeline 执行回调
        doAnswer(inv -> {
            SessionCallback<?> callback = inv.getArgument(0);
            RedisOperations ops = mock(RedisOperations.class);
            ValueOperations vOps = mock(ValueOperations.class);
            when(ops.opsForValue()).thenReturn(vOps);
            return callback.execute(ops);
        }).when(redisTemplate).executePipelined(any(SessionCallback.class));

        // 批量加载器：item:2 查到了，item:3 数据库也没有
        AtomicInteger batchLoaderCalls = new AtomicInteger(0);
        Map<String, String> result = cacheTemplate.batchGetOrLoad(keys, String.class, missingKeys -> {
            batchLoaderCalls.incrementAndGet();
            assertThat(missingKeys).containsExactly("item:2", "item:3");
            Map<String, String> dbData = new HashMap<>();
            dbData.put("item:2", "Val2");
            return dbData;
        });

        assertThat(result).hasSize(2);
        assertThat(result.get("item:1")).isEqualTo("Val1");
        assertThat(result.get("item:2")).isEqualTo("Val2");
        assertThat(result.get("item:3")).isNull(); // 未查到

        assertThat(batchLoaderCalls.get()).isEqualTo(1); // 单次批量调用，杜绝 N+1
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("10. 批量全部命中直接返回，不争锁不查库")
    void testBatchAllHit() {
        List<String> keys = Arrays.asList("k1", "k2");
        when(valueOperations.multiGet(Arrays.asList("app:k1", "app:k2")))
                .thenReturn(Arrays.asList("V1", "V2"));

        Map<String, String> result = cacheTemplate.batchGetOrLoad(keys, String.class, missing -> {
            throw new AssertionError("不应调用 batchLoader");
        });

        assertThat(result).containsEntry("k1", "V1").containsEntry("k2", "V2");
        verify(redissonClient, never()).getMultiLock(any(RLock[].class));
    }

    @Test
    @DisplayName("11. 对称运维操作测试 (get, put, evict, batchEvict, hasKey)")
    void testSymmetricOperations() {
        // hasKey
        when(redisTemplate.hasKey("app:user:1")).thenReturn(true);
        assertThat(cacheTemplate.hasKey("user:1")).isTrue();

        // put
        cacheTemplate.put("user:2", "data", Duration.ofMinutes(5));
        verify(valueOperations).set(eq("app:user:2"), eq("data"), any(Duration.class));

        // evict
        when(redisTemplate.delete("app:user:3")).thenReturn(true);
        assertThat(cacheTemplate.evict("user:3")).isTrue();

        // batchEvict
        when(redisTemplate.delete(Arrays.asList("app:u1", "app:u2"))).thenReturn(2L);
        assertThat(cacheTemplate.batchEvict(Arrays.asList("u1", "u2"))).isEqualTo(2L);
    }
}
