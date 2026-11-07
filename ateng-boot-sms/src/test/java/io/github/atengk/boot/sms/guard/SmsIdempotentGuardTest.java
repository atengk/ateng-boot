package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出站短信幂等防重卫士单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
@ExtendWith(MockitoExtension.class)
class SmsIdempotentGuardTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("SmsIdempotentGuard: SHA-256 幂等指纹生成测试")
    void testGenerateKey() {
        SmsIdempotentGuard guard = new InMemorySmsIdempotentGuard();

        SmsRequest req1 = SmsRequest.builder()
                .phone("13800138000")
                .templateId("SMS_1001")
                .params(SmsParam.of("code", "123456"))
                .build();

        SmsRequest req2 = SmsRequest.builder()
                .phone("13800138000")
                .templateId("SMS_1001")
                .params(SmsParam.of("code", "123456"))
                .build();

        SmsRequest reqDifferentParam = SmsRequest.builder()
                .phone("13800138000")
                .templateId("SMS_1001")
                .params(SmsParam.of("code", "654321"))
                .build();

        String key1 = guard.generateKey(req1);
        String key2 = guard.generateKey(req2);
        String keyDiff = guard.generateKey(reqDifferentParam);

        assertThat(key1).isNotBlank().isEqualTo(key2);
        assertThat(key1).isNotEqualTo(keyDiff);
    }

    @Test
    @DisplayName("InMemorySmsIdempotentGuard: 加锁、防重与释放锁闭环")
    void testInMemoryIdempotentGuard() {
        InMemorySmsIdempotentGuard guard = new InMemorySmsIdempotentGuard();
        String testKey = "test-idem-key-1";

        // 首次加锁成功
        boolean first = guard.acquireLock(testKey, Duration.ofSeconds(5));
        assertThat(first).isTrue();

        // 幂等窗口内重复加锁被拦截
        boolean duplicate = guard.acquireLock(testKey, Duration.ofSeconds(5));
        assertThat(duplicate).isFalse();

        // 显式释放锁
        guard.releaseLock(testKey);

        // 释放后再次加锁成功
        boolean reacquire = guard.acquireLock(testKey, Duration.ofSeconds(5));
        assertThat(reacquire).isTrue();

        // 空键安全放行
        assertThat(guard.acquireLock(null, Duration.ofMinutes(1))).isTrue();
        assertThat(guard.acquireLock("", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    @DisplayName("RedisSmsIdempotentGuard: 分布式 SETNX 与释放锁测试")
    void testRedisIdempotentGuard() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisSmsIdempotentGuard guard = new RedisSmsIdempotentGuard(stringRedisTemplate);

        String testKey = "test-redis-key-1";

        // 首次加锁成功 (SETNX 返回 true)
        when(valueOperations.setIfAbsent(eq("ateng:sms:idempotent:" + testKey), eq("1"), any(Duration.class)))
                .thenReturn(true);
        boolean first = guard.acquireLock(testKey, Duration.ofMinutes(3));
        assertThat(first).isTrue();

        // 重复加锁失败 (SETNX 返回 false)
        when(valueOperations.setIfAbsent(eq("ateng:sms:idempotent:" + testKey), eq("1"), any(Duration.class)))
                .thenReturn(false);
        boolean duplicate = guard.acquireLock(testKey, Duration.ofMinutes(3));
        assertThat(duplicate).isFalse();

        // 释放锁
        guard.releaseLock(testKey);
        verify(stringRedisTemplate, times(1)).delete("ateng:sms:idempotent:" + testKey);
    }

    @Test
    @DisplayName("RedisSmsIdempotentGuard: Redis 宕机异常弹性降级保底放行")
    void testRedisIdempotentGuardFailOpen() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis cluster down"));
        RedisSmsIdempotentGuard guard = new RedisSmsIdempotentGuard(stringRedisTemplate);

        // Redis 宕机时，acquireLock 保底返回 true，防御业务因 Redis 异常导致发信完全中断
        boolean result = guard.acquireLock("some-key", Duration.ofMinutes(1));
        assertThat(result).isTrue();
    }
}
