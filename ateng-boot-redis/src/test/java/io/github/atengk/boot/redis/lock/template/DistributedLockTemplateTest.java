package io.github.atengk.boot.redis.lock.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编程式分布式锁执行模板单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedLockTemplate 单元测试")
class DistributedLockTemplateTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private RedisProperties redisProperties;
    private DistributedLockTemplate lockTemplate;

    @BeforeEach
    void setUp() {
        redisProperties = new RedisProperties();
        redisProperties.getLock().setPrefix("lock:");
        redisProperties.getLock().setWaitTime(3000);
        redisProperties.getLock().setLeaseTime(-1);
        redisProperties.getLock().setSlowThresholdMs(5000);
        redisProperties.getLock().setTimeUnit(TimeUnit.MILLISECONDS);

        lockTemplate = new DistributedLockTemplate(redissonClient, redisProperties);
    }

    @Test
    @DisplayName("execute(supplier): 正常加锁执行并安全释放")
    void execute_supplier_success() throws Throwable {
        when(redissonClient.getLock("lock:order:1001")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        String result = lockTemplate.execute("order:1001", () -> "SUCCESS");

        assertThat(result).isEqualTo("SUCCESS");
        verify(rLock).tryLock(3000L, TimeUnit.MILLISECONDS);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("execute(action): 正常无返回值代码块加锁执行并安全释放")
    void execute_action_success() throws Throwable {
        when(redissonClient.getLock("lock:order:1002")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        AtomicBoolean executed = new AtomicBoolean(false);
        lockTemplate.execute("order:1002", () -> executed.set(true));

        assertThat(executed.get()).isTrue();
        verify(rLock).tryLock(3000L, TimeUnit.MILLISECONDS);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("execute: 闭包内抛出 BusinessException 时原样透传且安全释放锁")
    void execute_businessException_propagated() throws Throwable {
        when(redissonClient.getLock("lock:order:1003")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> lockTemplate.execute("order:1003", () -> {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "库存不足");
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessage("库存不足");

        verify(rLock).unlock();
    }

    @Test
    @DisplayName("execute: 闭包内抛出 CheckedException 时透传且安全释放锁")
    void execute_checkedException_propagated() throws Throwable {
        when(redissonClient.getLock("lock:order:1004")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> lockTemplate.execute("order:1004", () -> {
            throw new IOException("网络不可达");
        }))
                .isInstanceOf(IOException.class)
                .hasMessage("网络不可达");

        verify(rLock).unlock();
    }

    @Test
    @DisplayName("execute: 争锁超时快速失败抛出 HTTP 429 异常")
    void execute_timeout_throwsBusinessException() throws Throwable {
        when(redissonClient.getLock("lock:order:1005")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> lockTemplate.execute("order:1005", () -> "RESULT"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.TOO_MANY_REQUESTS.getCode());

        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("execute: 争锁超时触发降级回调")
    void execute_timeout_executesFallback() throws Throwable {
        when(redissonClient.getLock("lock:order:1006")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        String result = lockTemplate.execute("order:1006",
                () -> "PRIMARY",
                () -> "FALLBACK"
        );

        assertThat(result).isEqualTo("FALLBACK");
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("tryExecute: 争锁成功返回 true，争锁失败返回 false")
    void tryExecute_success_and_failure() throws Throwable {
        when(redissonClient.getLock("lock:job:1")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean success = lockTemplate.tryExecute("job:1", () -> executed.set(true));

        assertThat(success).isTrue();
        assertThat(executed.get()).isTrue();
        verify(rLock).unlock();

        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        boolean failure = lockTemplate.tryExecute("job:1", () -> executed.set(false));
        assertThat(failure).isFalse();
    }

    @Test
    @DisplayName("execute: 指定明确租约时间时不激活看门狗")
    void execute_explicit_leaseTime() throws Throwable {
        when(redissonClient.getLock("lock:order:1007")).thenReturn(rLock);
        when(rLock.tryLock(1000L, 5000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        String result = lockTemplate.execute("order:1007", LockMode.REENTRANT, 1000L, 5000L, TimeUnit.MILLISECONDS, () -> "OK");

        assertThat(result).isEqualTo("OK");
        verify(rLock).tryLock(1000L, 5000L, TimeUnit.MILLISECONDS);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("execute: 安全释放防御当前线程非持锁时不调用 unlock()")
    void execute_safeUnlock_defense() throws Throwable {
        when(redissonClient.getLock("lock:order:1008")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(false);

        String result = lockTemplate.execute("order:1008", () -> "OK");

        assertThat(result).isEqualTo("OK");
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("execute: 支持多键联锁与四阶模式")
    void execute_multiKeys_and_modes() throws Throwable {
        when(redissonClient.getFairLock("lock:fair:1")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        lockTemplate.execute(List.of("fair:1"), LockMode.FAIR, 3000L, -1L, TimeUnit.MILLISECONDS, () -> "FAIR_OK");
        verify(redissonClient).getFairLock("lock:fair:1");

        RReadWriteLock rwLock = mock(RReadWriteLock.class);
        when(redissonClient.getReadWriteLock("lock:rw:1")).thenReturn(rwLock);
        when(rwLock.readLock()).thenReturn(rLock);
        lockTemplate.execute(List.of("rw:1"), LockMode.READ, 3000L, -1L, TimeUnit.MILLISECONDS, () -> "READ_OK");
        verify(rwLock).readLock();

        when(rwLock.writeLock()).thenReturn(rLock);
        lockTemplate.execute(List.of("rw:1"), LockMode.WRITE, 3000L, -1L, TimeUnit.MILLISECONDS, () -> "WRITE_OK");
        verify(rwLock).writeLock();

        RLock lock1 = mock(RLock.class);
        RLock lock2 = mock(RLock.class);
        RLock multiLock = mock(RLock.class);
        when(redissonClient.getLock("lock:k1")).thenReturn(lock1);
        when(redissonClient.getLock("lock:k2")).thenReturn(lock2);
        when(redissonClient.getMultiLock(lock1, lock2)).thenReturn(multiLock);
        when(multiLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(multiLock.isHeldByCurrentThread()).thenReturn(true);

        lockTemplate.execute(List.of("k1", "k2"), LockMode.REENTRANT, 3000L, -1L, TimeUnit.MILLISECONDS, () -> "MULTI_OK");
        verify(redissonClient).getMultiLock(lock1, lock2);
        verify(multiLock).unlock();
    }

    @Test
    @DisplayName("execute: 支持传入自定义前缀且与全局前缀正确拼接")
    void execute_custom_prefix() throws Throwable {
        when(redissonClient.getLock("custom:order:1009")).thenReturn(rLock);
        when(rLock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        String result = lockTemplate.execute(List.of("order:1009"), "custom:", LockMode.REENTRANT, 3000L, -1L, TimeUnit.MILLISECONDS, null, () -> "CUSTOM_OK", null);

        assertThat(result).isEqualTo("CUSTOM_OK");
        verify(redissonClient).getLock("custom:order:1009");
        verify(rLock).unlock();
    }
}
