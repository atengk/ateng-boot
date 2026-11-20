package io.github.atengk.boot.job.snail.retry;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.context.RedisSnailRetryContextStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.slf4j.MDC;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisSnailRetryContextStore} 分布式快照序列化与 Fail-Open 容错降级单元测试
 *
 * @author Ateng
 * @since 2026-11-20
 */
class RedisSnailRetryContextStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private Map<String, String> redisStorage;
    private Map<String, Duration> redisTtls;
    private AtomicBoolean failRedisOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisStorage = new ConcurrentHashMap<>();
        redisTtls = new ConcurrentHashMap<>();
        failRedisOps = new AtomicBoolean(false);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doAnswer(invocation -> {
            if (failRedisOps.get()) {
                throw new IllegalStateException("Simulated Redis timeout");
            }
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            Duration ttl = invocation.getArgument(2);
            redisStorage.put(key, value);
            redisTtls.put(key, ttl);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        when(valueOperations.get(anyString())).thenAnswer((Answer<String>) invocation -> {
            if (failRedisOps.get()) {
                throw new IllegalStateException("Simulated Redis read failure");
            }
            return redisStorage.get(invocation.getArgument(0));
        });

        when(redisTemplate.delete(anyString())).thenAnswer((Answer<Boolean>) invocation -> {
            if (failRedisOps.get()) {
                throw new IllegalStateException("Simulated Redis delete failure");
            }
            String key = invocation.getArgument(0);
            redisTtls.remove(key);
            return redisStorage.remove(key) != null;
        });

        when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer((Answer<Cursor<String>>) invocation -> {
            if (failRedisOps.get()) {
                throw new IllegalStateException("Simulated Redis scan failure");
            }
            Iterator<String> iterator = List.copyOf(redisStorage.keySet()).iterator();
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
            when(cursor.next()).thenAnswer(inv -> iterator.next());
            return cursor;
        });

        when(redisTemplate.delete(any(Collection.class))).thenAnswer((Answer<Long>) invocation -> {
            if (failRedisOps.get()) {
                throw new IllegalStateException("Simulated Redis batch delete failure");
            }
            Collection<?> keys = invocation.getArgument(0);
            long deleted = 0L;
            for (Object key : keys) {
                redisTtls.remove(String.valueOf(key));
                if (redisStorage.remove(String.valueOf(key)) != null) {
                    deleted++;
                }
            }
            return deleted;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("RedisSnailRetryContextStore: 正确序列化还原四维上下文快照并写入固定前缀与配置 TTL")
    void testPutGetRemoveWithFourDimensionalSnapshot() {
        SnailJobProperties properties = new SnailJobProperties();
        properties.getRetry().setContextTtl(Duration.ofHours(12));
        RedisSnailRetryContextStore store = new RedisSnailRetryContextStore(redisTemplate, properties);

        MDC.put("traceId", "trace-redis-001");
        MDC.put("bizTag", "checkout");
        UserContextHolder.set("user-redis-99");
        TenantContextHolder.setTenantId("tenant-redis-88");
        TenantContextHolder.setIgnoreTenant(true);
        AsyncContext.ContextSnapshot captured = AsyncContext.ContextSnapshot.capture();

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        store.put("orderPay:IDEM-1", captured);

        String expectedRedisKey = RedisSnailRetryContextStore.KEY_PREFIX + "orderPay:IDEM-1";
        assertThat(redisStorage).containsKey(expectedRedisKey);
        assertThat(redisTtls.get(expectedRedisKey)).isEqualTo(Duration.ofHours(12));

        AsyncContext.ContextSnapshot restored = store.get("orderPay:IDEM-1");
        assertThat(restored).isNotNull();

        // 验证获取后不污染调用线程，apply 后精准还原四维现场
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.getExplicit()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        restored.apply();
        assertThat(MDC.get("traceId")).isEqualTo("trace-redis-001");
        assertThat(MDC.get("bizTag")).isEqualTo("checkout");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("user-redis-99");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-redis-88");
        assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();

        store.remove("orderPay:IDEM-1");
        assertThat(redisStorage).doesNotContainKey(expectedRedisKey);
        assertThat(store.get("orderPay:IDEM-1")).isNull();

        store.put("orderPay:IDEM-2", captured);
        assertThat(redisStorage).containsKey(RedisSnailRetryContextStore.KEY_PREFIX + "orderPay:IDEM-2");
        store.clear();
        assertThat(redisStorage).isEmpty();
    }

    @Test
    @DisplayName("RedisSnailRetryContextStore: Redis 异常时 Fail-Open 降级到本地有界存储且不阻断主链路")
    void testFailOpenFallbackToInMemoryWhenRedisThrows() {
        SnailJobProperties properties = new SnailJobProperties();
        properties.getRetry().setInMemoryMaxSize(32);
        RedisSnailRetryContextStore store = new RedisSnailRetryContextStore(redisTemplate, properties);

        MDC.put("traceId", "trace-fail-open");
        UserContextHolder.set("user-fail-open");
        TenantContextHolder.setTenantId("tenant-fail-open");
        AsyncContext.ContextSnapshot captured = AsyncContext.ContextSnapshot.capture();

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        failRedisOps.set(true);
        store.put("failOpenKey", captured);

        AsyncContext.ContextSnapshot restored = store.get("failOpenKey");
        assertThat(restored).isNotNull();
        restored.apply();
        assertThat(MDC.get("traceId")).isEqualTo("trace-fail-open");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("user-fail-open");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-fail-open");

        store.remove("failOpenKey");
        assertThat(store.get("failOpenKey")).isNull();
    }

    @Test
    @DisplayName("RedisSnailRetryContextStore: 非法 JSON 负载与空入参防御不抛异常")
    void testCorruptedJsonAndNullInputDefense() {
        RedisSnailRetryContextStore store = new RedisSnailRetryContextStore(redisTemplate, new SnailJobProperties());
        store.put(null, AsyncContext.ContextSnapshot.capture());
        store.put("k", null);
        assertThat(store.get(null)).isNull();
        store.remove(null);

        redisStorage.put(RedisSnailRetryContextStore.KEY_PREFIX + "corrupted", "{not-valid-json");
        assertThat(store.get("corrupted")).isNull();
    }
}
