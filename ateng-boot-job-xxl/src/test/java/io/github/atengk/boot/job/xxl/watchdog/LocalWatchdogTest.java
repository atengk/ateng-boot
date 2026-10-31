package io.github.atengk.boot.job.xxl.watchdog;

import io.github.atengk.boot.job.xxl.event.JobTimeoutEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 本地硬超时看门狗单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
class LocalWatchdogTest {

    private LocalWatchdog watchdog;
    private AtomicReference<JobTimeoutEvent> capturedEvent;

    @BeforeEach
    void setUp() {
        capturedEvent = new AtomicReference<>();
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof JobTimeoutEvent timeoutEvent) {
                capturedEvent.set(timeoutEvent);
            }
        };
        watchdog = new LocalWatchdog(eventPublisher);
    }

    @AfterEach
    void tearDown() {
        if (watchdog != null) {
            watchdog.destroy();
        }
    }

    @Test
    @DisplayName("测试正常完成的任务：看门狗取消后不触发中断也不发布超时事件")
    void testNormalExecutionNoInterruption() throws InterruptedException {
        JobExecutionMetadata metadata = new JobExecutionMetadata(1L, "quickJob", 1001L, "trace-1", "tenant-1");
        LocalWatchdog.WatchdogSession session = watchdog.startWatch(Thread.currentThread(), Duration.ofMillis(500), metadata);

        // 快速模拟业务完成
        Thread.sleep(50);
        session.cancel();

        // 等待看门狗周期过后验证无中断、无超时事件
        Thread.sleep(600);
        assertThat(session.isTimedOut()).isFalse();
        assertThat(capturedEvent.get()).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("测试超时任务：看门狗精准触发 Thread.interrupt() 强中断并广播 JobTimeoutEvent 事件")
    void testTimeoutTriggersInterruptionAndEvent() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            JobExecutionMetadata metadata = new JobExecutionMetadata(99L, "slowJob", 8888L, "trace-888", "tenant-999");
            LocalWatchdog.WatchdogSession session = watchdog.startWatch(Thread.currentThread(), Duration.ofMillis(200), metadata);
            try {
                // 模拟长时间阻塞
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            } finally {
                session.cancel();
                latch.countDown();
            }
        }, "test-worker-thread");

        worker.start();
        boolean finished = latch.await(1500, TimeUnit.MILLISECONDS);

        assertThat(finished).isTrue();
        assertThat(wasInterrupted.get()).isTrue();

        // 校验广播发布的超时事件详情
        JobTimeoutEvent event = capturedEvent.get();
        assertThat(event).isNotNull();
        assertThat(event.getJobId()).isEqualTo(99L);
        assertThat(event.getJobName()).isEqualTo("slowJob");
        assertThat(event.getLogId()).isEqualTo(8888L);
        assertThat(event.getTraceId()).isEqualTo("trace-888");
        assertThat(event.getTenantId()).isEqualTo("tenant-999");
        assertThat(event.getThreadName()).isEqualTo("test-worker-thread");
        assertThat(event.getTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(event.getExecutionDuration().toMillis()).isGreaterThanOrEqualTo(150);
    }

}
