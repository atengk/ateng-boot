package io.github.atengk.boot.web.idempotent.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 基于 StringRedisTemplate 的分布式幂等存储单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisIdempotentKeyStore 单元测试")
class RedisIdempotentKeyStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisIdempotentKeyStore keyStore;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        keyStore = new RedisIdempotentKeyStore(redisTemplate);
    }

    @Test
    @DisplayName("首次加锁调用 setIfAbsent 成功返回 true")
    void shouldReturnTrueWhenSetIfAbsentSucceeds() {
        when(valueOperations.setIfAbsent(eq("idempotent:test:1"), eq("1"), eq(5L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);

        boolean locked = keyStore.tryLock("idempotent:test:1", 5, TimeUnit.SECONDS);

        assertThat(locked).isTrue();
        verify(valueOperations, times(1)).setIfAbsent(eq("idempotent:test:1"), eq("1"), eq(5L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("并发重复请求时 setIfAbsent 失败返回 false")
    void shouldReturnFalseWhenKeyAlreadyExists() {
        when(valueOperations.setIfAbsent(eq("idempotent:test:1"), eq("1"), eq(5L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        boolean locked = keyStore.tryLock("idempotent:test:1", 5, TimeUnit.SECONDS);

        assertThat(locked).isFalse();
    }

    @Test
    @DisplayName("主动释放锁时调用 delete 方法")
    void shouldDeleteKeyWhenUnlock() {
        keyStore.unlock("idempotent:test:1");

        verify(redisTemplate, times(1)).delete("idempotent:test:1");
    }
}
