package io.github.atengk.boot.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线程池全景状态快照单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("线程池运行时状态快照测试")
class ThreadPoolStatusTest {

    @Test
    @DisplayName("基于原生 ThreadPoolExecutor 正确抓取空闲状态指标")
    void shouldCaptureMetricsFromThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10)
        );

        try {
            ThreadPoolStatus status = ThreadPoolStatus.of(executor);

            assertThat(status.corePoolSize()).isEqualTo(2);
            assertThat(status.maxPoolSize()).isEqualTo(4);
            assertThat(status.poolSize()).isEqualTo(0);
            assertThat(status.activeCount()).isEqualTo(0);
            assertThat(status.queueSize()).isEqualTo(0);
            assertThat(status.remainingQueueCapacity()).isEqualTo(10);
            assertThat(status.completedTaskCount()).isEqualTo(0L);
            assertThat(status.isSaturated()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("当线程池活跃线程达上限且队列无剩余空间时判定为饱和状态")
    void shouldDetectSaturatedState() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1)
        );

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);

        try {
            // 提交占位任务占用核心线程
            executor.execute(() -> {
                taskStarted.countDown();
                try {
                    taskRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });

            // 等待任务占位成功
            assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // 提交排队任务占满队列（容量 1）
            executor.execute(() -> {});

            ThreadPoolStatus saturatedStatus = ThreadPoolStatus.of(executor);
            assertThat(saturatedStatus.poolSize()).isEqualTo(1);
            assertThat(saturatedStatus.activeCount()).isEqualTo(1);
            assertThat(saturatedStatus.queueSize()).isEqualTo(1);
            assertThat(saturatedStatus.remainingQueueCapacity()).isEqualTo(0);
            assertThat(saturatedStatus.isSaturated()).isTrue();
        } finally {
            taskRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("基于 Spring ThreadPoolTaskExecutor 正确抓取运行时指标")
    void shouldCaptureMetricsFromThreadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(20);
        executor.initialize();

        try {
            ThreadPoolStatus status = ThreadPoolStatus.of(executor);

            assertThat(status.corePoolSize()).isEqualTo(3);
            assertThat(status.maxPoolSize()).isEqualTo(6);
            assertThat(status.remainingQueueCapacity()).isEqualTo(20);
            assertThat(status.isSaturated()).isFalse();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("当 ThreadPoolTaskExecutor 尚未初始化时优雅降级返回配置层指标")
    void shouldFallbackWhenThreadPoolTaskExecutorNotInitialized() {
        ThreadPoolTaskExecutor uninitializedExecutor = new ThreadPoolTaskExecutor();
        uninitializedExecutor.setCorePoolSize(5);
        uninitializedExecutor.setMaxPoolSize(10);
        uninitializedExecutor.setQueueCapacity(50);

        ThreadPoolStatus status = ThreadPoolStatus.of(uninitializedExecutor);

        assertThat(status.corePoolSize()).isEqualTo(5);
        assertThat(status.maxPoolSize()).isEqualTo(10);
        assertThat(status.poolSize()).isEqualTo(0);
        assertThat(status.activeCount()).isEqualTo(0);
        assertThat(status.queueSize()).isEqualTo(0);
        assertThat(status.remainingQueueCapacity()).isEqualTo(50);
        assertThat(status.isSaturated()).isFalse();
    }

    @Test
    @DisplayName("当传入 null 执行器时抛出明确空指针断言")
    void shouldThrowExceptionWhenExecutorIsNull() {
        assertThatThrownBy(() -> ThreadPoolStatus.of((ThreadPoolExecutor) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("executor 不能为空");

        assertThatThrownBy(() -> ThreadPoolStatus.of((ThreadPoolTaskExecutor) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskExecutor 不能为空");
    }
}
