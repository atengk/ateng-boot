package io.github.atengk.boot.redis.lock.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 声明式分布式锁切面单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedLockAspect 单元测试")
class DistributedLockAspectTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private RLock rLock;

    private RedisProperties redisProperties;
    private DistributedLockAspect aspect;

    static class SampleService {

        @DistributedLock(key = "#orderId")
        public String processOrder(String orderId) {
            return "ok:" + orderId;
        }

        @DistributedLock(key = "#id", mode = LockMode.FAIR)
        public String processFair(String id) {
            return "fair:" + id;
        }

        @DistributedLock(key = "#id", mode = LockMode.READ)
        public String processRead(String id) {
            return "read:" + id;
        }

        @DistributedLock(key = "#id", mode = LockMode.WRITE)
        public String processWrite(String id) {
            return "write:" + id;
        }

        @DistributedLock(keys = {"#fromUser", "#toUser"})
        public String transfer(String fromUser, String toUser) {
            return "transfer:" + fromUser + "->" + toUser;
        }

        @DistributedLock(key = "#id", leaseTime = 10)
        public String processExplicitLease(String id) {
            return "lease:" + id;
        }

        @DistributedLock(key = "#id")
        public String processSlow(String id) throws InterruptedException {
            Thread.sleep(30);
            return "slow:" + id;
        }
    }

    @BeforeEach
    void setUp() {
        redisProperties = new RedisProperties();
        aspect = new DistributedLockAspect(redissonClient, redisProperties);
    }

    private void mockJoinPoint(String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method method = SampleService.class.getMethod(methodName, paramTypes);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
    }

    @Test
    @DisplayName("可重入锁获取成功且执行后安全释放（激活看门狗续期）")
    void shouldAcquireReentrantLockAndReleaseSafely() throws Throwable {
        mockJoinPoint("processOrder", new Class<?>[]{String.class}, new Object[]{"ORD_1001"});
        when(joinPoint.proceed()).thenReturn("ok:ORD_1001");
        when(redissonClient.getLock("lock:ORD_1001")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Method method = SampleService.class.getMethod("processOrder", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("ok:ORD_1001");
        verify(rLock).tryLock(eq(3L), eq(TimeUnit.SECONDS));
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("公平锁模式获取验证")
    void shouldAcquireFairLock() throws Throwable {
        mockJoinPoint("processFair", new Class<?>[]{String.class}, new Object[]{"FAIR_01"});
        when(joinPoint.proceed()).thenReturn("fair:FAIR_01");
        when(redissonClient.getFairLock("lock:FAIR_01")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Method method = SampleService.class.getMethod("processFair", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("fair:FAIR_01");
        verify(redissonClient).getFairLock("lock:FAIR_01");
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("读写锁读锁模式获取验证")
    void shouldAcquireReadLock() throws Throwable {
        mockJoinPoint("processRead", new Class<?>[]{String.class}, new Object[]{"RW_01"});
        when(joinPoint.proceed()).thenReturn("read:RW_01");

        RReadWriteLock rwLock = mock(RReadWriteLock.class);
        when(redissonClient.getReadWriteLock("lock:RW_01")).thenReturn(rwLock);
        when(rwLock.readLock()).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Method method = SampleService.class.getMethod("processRead", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("read:RW_01");
        verify(rwLock).readLock();
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("读写锁写锁模式获取验证")
    void shouldAcquireWriteLock() throws Throwable {
        mockJoinPoint("processWrite", new Class<?>[]{String.class}, new Object[]{"RW_02"});
        when(joinPoint.proceed()).thenReturn("write:RW_02");

        RReadWriteLock rwLock = mock(RReadWriteLock.class);
        when(redissonClient.getReadWriteLock("lock:RW_02")).thenReturn(rwLock);
        when(rwLock.writeLock()).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Method method = SampleService.class.getMethod("processWrite", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("write:RW_02");
        verify(rwLock).writeLock();
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("多键联锁 MultiLock 解析与原子获取验证")
    void shouldAcquireMultiLockForMultipleKeys() throws Throwable {
        mockJoinPoint("transfer", new Class<?>[]{String.class, String.class}, new Object[]{"Alice", "Bob"});
        when(joinPoint.proceed()).thenReturn("transfer:Alice->Bob");

        RLock lock1 = mock(RLock.class);
        RLock lock2 = mock(RLock.class);
        when(redissonClient.getLock("lock:Alice")).thenReturn(lock1);
        when(redissonClient.getLock("lock:Bob")).thenReturn(lock2);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Method method = SampleService.class.getMethod("transfer", String.class, String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("transfer:Alice->Bob");
        verify(redissonClient).getMultiLock(any(RLock[].class));
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("获取锁超时快速熔断并抛出 BusinessException(429)")
    void shouldThrowBusinessExceptionOnLockTimeout() throws Exception {
        mockJoinPoint("processOrder", new Class<?>[]{String.class}, new Object[]{"ORD_TIMEOUT"});
        when(redissonClient.getLock("lock:ORD_TIMEOUT")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        Method method = SampleService.class.getMethod("processOrder", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(BusinessException.class)
                .hasMessage("系统繁忙，获取分布式锁超时，请稍后再试")
                .extracting("code")
                .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());

        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("显式指定 leaseTime 时不启用看门狗而调用带租约的 tryLock")
    void shouldCallTryLockWithLeaseTime() throws Throwable {
        mockJoinPoint("processExplicitLease", new Class<?>[]{String.class}, new Object[]{"LEASE_01"});
        when(joinPoint.proceed()).thenReturn("lease:LEASE_01");
        when(redissonClient.getLock("lock:LEASE_01")).thenReturn(rLock);
        when(rLock.tryLock(eq(3L), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Method method = SampleService.class.getMethod("processExplicitLease", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("lease:LEASE_01");
        verify(rLock).tryLock(eq(3L), eq(10L), eq(TimeUnit.SECONDS));
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("安全防呆：业务异常且锁提前过期失效时，跳过 unlock 不掩盖原业务异常")
    void shouldNotThrowWhenLockAlreadyLostAndThrowOriginalBusinessException() throws Throwable {
        mockJoinPoint("processOrder", new Class<?>[]{String.class}, new Object[]{"ORD_EXPIRED"});
        when(redissonClient.getLock("lock:ORD_EXPIRED")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("原业务数据校验不合法"));
        // 模拟锁已超时，当前线程不再持有
        when(rLock.isHeldByCurrentThread()).thenReturn(false);

        Method method = SampleService.class.getMethod("processOrder", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("原业务数据校验不合法");

        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("慢锁告警审计验证：执行耗时超过阈值正常记录日志且不影响方法返回")
    void shouldAuditSlowLockExecution() throws Throwable {
        mockJoinPoint("processSlow", new Class<?>[]{String.class}, new Object[]{"SLOW_01"});
        when(joinPoint.proceed()).thenReturn("slow:SLOW_01");
        when(redissonClient.getLock("lock:SLOW_01")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // 设置极低的慢锁阈值触发告警分支
        redisProperties.getLock().setSlowThresholdMs(10L);

        Method method = SampleService.class.getMethod("processSlow", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("slow:SLOW_01");
        verify(rLock).unlock();
    }
}
