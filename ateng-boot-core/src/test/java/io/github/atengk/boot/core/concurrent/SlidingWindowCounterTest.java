package io.github.atengk.boot.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存滑动窗口计数器单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("内存滑动窗口计数器 SlidingWindowCounter 测试")
class SlidingWindowCounterTest {

    @Test
    @DisplayName("基础递增与总数统计验证")
    void shouldIncrementAndCountAccurately() {
        SlidingWindowCounter counter = new SlidingWindowCounter(Duration.ofMillis(500), 5);

        assertThat(counter.getCount()).isEqualTo(0);
        assertThat(counter.incrementAndGet()).isEqualTo(1);
        assertThat(counter.incrementAndGet(3)).isEqualTo(4);
        assertThat(counter.getCount()).isEqualTo(4);

        counter.reset();
        assertThat(counter.getCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("tryAcquire 限流放行与阻断验证")
    void shouldAcquireWithinThreshold() {
        SlidingWindowCounter counter = new SlidingWindowCounter(Duration.ofMillis(500), 5);

        // 阈值限制为 3
        assertThat(counter.tryAcquire(3)).isTrue();
        assertThat(counter.tryAcquire(3)).isTrue();
        assertThat(counter.tryAcquire(3)).isTrue();
        // 第 4 次尝试超出阈值，拦截
        assertThat(counter.tryAcquire(3)).isFalse();
        assertThat(counter.getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("时间窗口滑动后过期切片自动扣减淘汰")
    void shouldExpireOldSlicesAfterSlidingWindow() throws Exception {
        // 总窗口 100ms，2 个切片（每个切片 50ms）
        SlidingWindowCounter counter = new SlidingWindowCounter(Duration.ofMillis(100), 2);

        counter.incrementAndGet(5);
        assertThat(counter.getCount()).isEqualTo(5);

        // 休眠等待窗口滑过
        Thread.sleep(150);

        assertThat(counter.getCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("多线程并发 tryAcquire 严格遵守阈值不发生超额放行")
    void shouldHandleConcurrentAcquisitionSafely() throws Exception {
        SlidingWindowCounter counter = new SlidingWindowCounter(Duration.ofSeconds(2), 10);
        int threads = 10;
        int requestsPerThread = 20;
        int threshold = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> {
                    try {
                        startGate.await();
                        for (int j = 0; j < requestsPerThread; j++) {
                            if (counter.tryAcquire(threshold)) {
                                successCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(endGate.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(successCount.get()).isEqualTo(threshold);
            assertThat(counter.getCount()).isEqualTo(threshold);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("参数校验与异常防御")
    void shouldValidateArguments() {
        assertThatThrownBy(() -> new SlidingWindowCounter(null, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("windowDuration 不能为空");

        assertThatThrownBy(() -> new SlidingWindowCounter(Duration.ZERO, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowDuration 必须大于 0");

        assertThatThrownBy(() -> new SlidingWindowCounter(Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sliceCount 必须大于 0");

        SlidingWindowCounter counter = new SlidingWindowCounter();
        assertThatThrownBy(() -> counter.incrementAndGet(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta 必须大于 0");

        assertThatThrownBy(() -> counter.tryAcquire(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta 必须大于 0");
    }
}
