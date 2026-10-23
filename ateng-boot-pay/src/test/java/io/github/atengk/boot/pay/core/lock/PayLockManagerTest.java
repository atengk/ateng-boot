package io.github.atengk.boot.pay.core.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付防重并发锁双轨管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@ExtendWith(MockitoExtension.class)
class PayLockManagerTest {

    private InMemoryPayLockManager inMemoryLockManager;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        inMemoryLockManager = new InMemoryPayLockManager();
    }

    // ==========================================
    // 1. InMemoryPayLockManager 测试
    // ==========================================

    @Test
    @DisplayName("InMemory: 锁获取、竞争与主动释放")
    void testInMemoryLockAndUnlock() {
        String lockKey = "WECHAT_PAY:ORDER_1001";
        Duration timeout = Duration.ofSeconds(5);

        // 1. 首次获取锁成功
        boolean firstLock = inMemoryLockManager.tryLock(lockKey, timeout);
        assertTrue(firstLock);

        // 2. 锁未过期且未释放时并发争抢失败
        boolean secondLock = inMemoryLockManager.tryLock(lockKey, timeout);
        assertFalse(secondLock);

        // 3. 主动释放锁
        inMemoryLockManager.unlock(lockKey);

        // 4. 释放后重新获取锁成功
        boolean reLock = inMemoryLockManager.tryLock(lockKey, timeout);
        assertTrue(reLock);

        // 清理现场
        inMemoryLockManager.clear();
    }

    @Test
    @DisplayName("InMemory: 锁超时自然过期与重新竞争")
    void testInMemoryLockExpiration() throws InterruptedException {
        String lockKey = "ALIPAY:ORDER_2002";
        Duration shortTimeout = Duration.ofMillis(60);

        assertTrue(inMemoryLockManager.tryLock(lockKey, shortTimeout));
        assertFalse(inMemoryLockManager.tryLock(lockKey, shortTimeout));

        // 等待超时
        Thread.sleep(80);

        // 超时后自动允许新线程获取
        assertTrue(inMemoryLockManager.tryLock(lockKey, Duration.ofSeconds(5)));
        inMemoryLockManager.unlock(lockKey);
    }

    // ==========================================
    // 2. RedisPayLockManager 测试
    // ==========================================

    @Test
    @DisplayName("Redis: 基于 SETNX 的分布式锁安全获取与持有者防御性释放")
    void testRedisLockAndUnlock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("ateng:pay:lock:WECHAT_PAY:ORDER_3003"), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);

        RedisPayLockManager redisLockManager = new RedisPayLockManager(redisTemplate);

        String lockKey = "WECHAT_PAY:ORDER_3003";

        // 1. 首次获取成功
        assertTrue(redisLockManager.tryLock(lockKey, Duration.ofSeconds(10)));

        // 2. 二次并发获取失败
        assertFalse(redisLockManager.tryLock(lockKey, Duration.ofSeconds(10)));

        // 3. 持有者安全释放锁
        redisLockManager.unlock(lockKey);
        verify(redisTemplate, times(1)).execute(any(), eq(java.util.Collections.singletonList("ateng:pay:lock:WECHAT_PAY:ORDER_3003")), anyString());

        // 4. 未持有锁时再次释放不应再执行 Redis 解锁
        redisLockManager.unlock(lockKey);
        verify(redisTemplate, times(1)).execute(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("Redis: 网络异常弹性降级保底为 false")
    void testRedisLockExceptionHandling() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis connection refused"));

        RedisPayLockManager redisLockManager = new RedisPayLockManager(redisTemplate);

        // 发生 Redis 异常时不向上层抛出崩溃，而是安全返回 false
        assertFalse(redisLockManager.tryLock("WECHAT_PAY:ORDER_4004", Duration.ofSeconds(10)));
    }
}
