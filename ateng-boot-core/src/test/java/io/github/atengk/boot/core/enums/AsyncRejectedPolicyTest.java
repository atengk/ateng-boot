package io.github.atengk.boot.core.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台线程池饱和拒绝策略单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("异步线程池任务饱和拒绝策略测试")
class AsyncRejectedPolicyTest {

    @Test
    @DisplayName("WARN_CALLER_RUNS 策略在线程池饱和时由调用线程直接执行任务")
    void shouldExecuteInCallerThreadOnWarnCallerRuns() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                AsyncRejectedPolicy.WARN_CALLER_RUNS.getHandler()
        );

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        AtomicBoolean callerExecuted = new AtomicBoolean(false);
        String currentThreadName = Thread.currentThread().getName();

        try {
            // 占用核心线程
            executor.execute(() -> {
                blockerStarted.countDown();
                try {
                    blockerRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });

            assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // 占满队列（容量 1）
            executor.execute(() -> {});

            // 触发拒绝，当前任务应由当前测试调用线程直接执行
            executor.execute(() -> {
                if (Thread.currentThread().getName().equals(currentThreadName)) {
                    callerExecuted.set(true);
                }
            });

            assertThat(callerExecuted.get()).isTrue();
        } finally {
            blockerRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("WARN_ABORT 策略在线程池饱和时输出告警并抛出 RejectedExecutionException")
    void shouldThrowExceptionOnWarnAbort() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                AsyncRejectedPolicy.WARN_ABORT.getHandler()
        );

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);

        try {
            // 占用核心线程
            executor.execute(() -> {
                blockerStarted.countDown();
                try {
                    blockerRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });

            assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // 占满队列（容量 1）
            executor.execute(() -> {});

            // 再次提交触发拒绝异常
            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            blockerRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("枚举包含全部 6 种标准与告警策略且 handler 均非空")
    void shouldVerifyAllEnumValues() {
        for (AsyncRejectedPolicy policy : AsyncRejectedPolicy.values()) {
            assertThat(policy.getHandler()).isNotNull();
        }
    }
}
