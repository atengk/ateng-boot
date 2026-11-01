package io.github.atengk.boot.job.xxl.governor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 节点级任务运行时治理总线单测
 *
 * @author Ateng
 * @since 2026-11-01
 */
class JobRuntimeGovernorTest {

    private JobRuntimeGovernor governor;

    @BeforeEach
    void setUp() {
        governor = new JobRuntimeGovernor();
    }

    @AfterEach
    void tearDown() {
        if (governor != null) {
            governor.clear();
        }
    }

    @Test
    @DisplayName("测试基础静默与恢复操作：pause, isPaused, resume, getPausedHandlers")
    void testBasicPauseAndResume() {
        assertThat(governor.isPaused("userSyncJob")).isFalse();
        assertThat(governor.getPausedHandlers()).isEmpty();

        // 1. 暂停任务
        governor.pause("userSyncJob");
        assertThat(governor.isPaused("userSyncJob")).isTrue();
        assertThat(governor.isPaused("  userSyncJob  ")).isTrue(); // 忽略空格
        assertThat(governor.getPausedHandlers()).containsExactly("userSyncJob");

        // 2. 暂停另一个任务
        governor.pause("orderCleanJob");
        assertThat(governor.getPausedHandlers()).containsExactly("orderCleanJob", "userSyncJob");

        // 3. 恢复第一个任务
        governor.resume("userSyncJob");
        assertThat(governor.isPaused("userSyncJob")).isFalse();
        assertThat(governor.getPausedHandlers()).containsExactly("orderCleanJob");

        // 4. 重复恢复已恢复的任务（幂等且不抛错）
        governor.resume("userSyncJob");
        assertThat(governor.isPaused("userSyncJob")).isFalse();

        // 5. 清理全部状态
        governor.clear();
        assertThat(governor.getPausedHandlers()).isEmpty();
        assertThat(governor.isPaused("orderCleanJob")).isFalse();
    }

    @Test
    @DisplayName("测试入参空值卫语句防卫")
    void testParameterValidation() {
        assertThatThrownBy(() -> governor.pause(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler 不能为空");

        assertThatThrownBy(() -> governor.pause("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler 不能为空");

        assertThatThrownBy(() -> governor.resume(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler 不能为空");

        assertThat(governor.isPaused(null)).isFalse();
        assertThat(governor.isPaused("")).isFalse();
    }

    @Test
    @DisplayName("测试高并发竞争安全：多线程并发 pause 与 resume")
    void testConcurrentThreadSafety() throws InterruptedException {
        int threadCount = 16;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String handler = "job-" + (threadId % 4);
                        governor.pause(handler);
                        assertThat(governor.isPaused(handler)).isTrue();
                        governor.resume(handler);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        executor.shutdown();
    }

    @Test
    @DisplayName("测试纯易失内存语义：实例重建模拟 Pod 重启后状态完全重置为活跃")
    void testVolatileInMemoryLifecycle() {
        governor.pause("paymentCheckJob");
        assertThat(governor.isPaused("paymentCheckJob")).isTrue();

        // 模拟容器重启，新实例初始化
        JobRuntimeGovernor restartedGovernor = new JobRuntimeGovernor();
        assertThat(restartedGovernor.isPaused("paymentCheckJob")).isFalse();
        assertThat(restartedGovernor.getPausedHandlers()).isEmpty();
    }
}
