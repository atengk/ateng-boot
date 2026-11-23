package io.github.atengk.boot.job.snail.drain;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.aspect.SnailRetryContextAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * GracefulDrainStateMachine 平滑停机排空状态机单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class GracefulDrainStateMachineTest {

    private SnailJobProperties properties;
    private GracefulDrainStateMachine stateMachine;

    @BeforeEach
    public void setUp() {
        properties = new SnailJobProperties();
        properties.setDrainTimeout(Duration.ofMillis(300));
        stateMachine = new GracefulDrainStateMachine(properties);
    }

    @Test
    public void testLifecycleAndCounters() {
        assertFalse(stateMachine.isRunning());
        stateMachine.start();
        assertTrue(stateMachine.isRunning());
        assertEquals(Integer.MAX_VALUE, stateMachine.getPhase());
        assertTrue(stateMachine.isAutoStartup());
        assertFalse(stateMachine.isDraining());

        assertEquals(1, stateMachine.incrementInFlightJob());
        assertEquals(1, stateMachine.getInFlightJobCount());

        assertEquals(1, stateMachine.incrementInFlightRetry());
        assertEquals(1, stateMachine.getInFlightRetryCount());
        assertEquals(2, stateMachine.getTotalInFlightCount());

        assertEquals(0, stateMachine.decrementInFlightJob());
        assertEquals(0, stateMachine.decrementInFlightRetry());
        assertEquals(0, stateMachine.getTotalInFlightCount());
    }

    @Test
    public void testSmoothDrainExecution() throws Exception {
        stateMachine.start();
        stateMachine.incrementInFlightJob();

        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // 异步启动 stop()
        new Thread(() -> {
            stateMachine.stop(() -> {
                completed.set(true);
                latch.countDown();
            });
        }).start();

        // 验证进入排空阶段
        Thread.sleep(50);
        assertTrue(stateMachine.isDraining());
        assertFalse(completed.get());

        // 完成在途任务
        stateMachine.decrementInFlightJob();

        boolean waitSuccess = latch.await(2, TimeUnit.SECONDS);
        assertTrue(waitSuccess);
        assertTrue(completed.get());
        assertFalse(stateMachine.isRunning());
    }

    @Test
    public void testDrainTimeoutHandling() throws Exception {
        properties.setDrainTimeout(Duration.ofMillis(100));
        stateMachine.start();
        stateMachine.incrementInFlightJob(); // 任务始终未释放

        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            stateMachine.stop(() -> {
                completed.set(true);
                latch.countDown();
            });
        }).start();

        boolean waitSuccess = latch.await(2, TimeUnit.SECONDS);
        assertTrue(waitSuccess, "超时后应当强制继续完成后续销毁");
        assertTrue(completed.get());
        assertFalse(stateMachine.isRunning());
    }

    public static class SampleTarget {
        public void executeJob() {
        }

        public void executeRetry() {
        }
    }

    @Test
    public void testJobAspectRejectsWhenDraining() throws NoSuchMethodException {
        stateMachine.setDraining(true);
        SnailJobContextAspect aspect = new SnailJobContextAspect(null, properties, stateMachine, null, null);

        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        Method method = SampleTarget.class.getMethod("executeJob");
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(new SampleTarget());

        BusinessException exception = assertThrows(BusinessException.class, () -> aspect.aroundJob(pjp));
        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("平滑停机排空阶段"));
    }

    @Test
    public void testRetryAspectRejectsWhenDraining() throws NoSuchMethodException {
        stateMachine.setDraining(true);
        SnailRetryContextAspect aspect = new SnailRetryContextAspect(stateMachine, null, null);

        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        Method method = SampleTarget.class.getMethod("executeRetry");
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(new SampleTarget());

        BusinessException exception = assertThrows(BusinessException.class, () -> aspect.aroundRetry(pjp));
        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("平滑停机排空阶段"));
    }
}
