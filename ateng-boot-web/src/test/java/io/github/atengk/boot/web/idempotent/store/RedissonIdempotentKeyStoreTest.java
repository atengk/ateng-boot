package io.github.atengk.boot.web.idempotent.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 基于 RedissonClient 的分布式幂等存储单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedissonIdempotentKeyStore 单元测试")
class RedissonIdempotentKeyStoreTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> bucket;

    private RedissonIdempotentKeyStore keyStore;

    @BeforeEach
    void setUp() {
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        keyStore = new RedissonIdempotentKeyStore(redissonClient);
    }

    @Test
    @DisplayName("首次加锁调用 setIfAbsent 成功返回 true")
    void shouldReturnTrueWhenRedissonSetIfAbsentSucceeds() {
        when(bucket.setIfAbsent(eq("1"), eq(Duration.ofMillis(5000)))).thenReturn(true);

        boolean locked = keyStore.tryLock("idempotent:test:2", 5, TimeUnit.SECONDS);

        assertThat(locked).isTrue();
        verify(bucket, times(1)).setIfAbsent(eq("1"), eq(Duration.ofMillis(5000)));
    }

    @Test
    @DisplayName("重复加锁调用 setIfAbsent 失败返回 false")
    void shouldReturnFalseWhenRedissonKeyAlreadyExists() {
        when(bucket.setIfAbsent(eq("1"), eq(Duration.ofMillis(5000)))).thenReturn(false);

        boolean locked = keyStore.tryLock("idempotent:test:2", 5, TimeUnit.SECONDS);

        assertThat(locked).isFalse();
    }

    @Test
    @DisplayName("主动释放锁时调用 delete 方法")
    void shouldDeleteBucketWhenUnlock() {
        keyStore.unlock("idempotent:test:2");

        verify(bucket, times(1)).delete();
    }
}
