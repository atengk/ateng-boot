package io.github.atengk.boot.ssh.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SftpProgressThrottler 流式文件传输进度平滑节流器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("SftpProgressThrottler 流式文件传输进度平滑节流器单元测试")
class SftpProgressThrottlerTest {

    @Test
    @DisplayName("测试工厂方法与参数防御性校验")
    void testFactoryAndValidation() {
        SftpProgressListener dummy = (transferred, total) -> {
        };

        // 1. 默认工厂
        SftpProgressThrottler throttler1 = SftpProgressThrottler.of(dummy);
        assertThat(throttler1.getDelegate()).isSameAs(dummy);
        assertThat(throttler1.getMinStepPercentage()).isEqualTo(1.0);
        assertThat(throttler1.getMinInterval()).isEqualTo(Duration.ofMillis(200));

        // 2. 自定义步长工厂
        SftpProgressThrottler throttler2 = SftpProgressThrottler.of(dummy, 5.0);
        assertThat(throttler2.getMinStepPercentage()).isEqualTo(5.0);
        assertThat(throttler2.getMinInterval()).isEqualTo(Duration.ofMillis(200));

        // 3. 自定义时间间隔工厂
        SftpProgressThrottler throttler3 = SftpProgressThrottler.of(dummy, Duration.ofMillis(500));
        assertThat(throttler3.getMinStepPercentage()).isEqualTo(1.0);
        assertThat(throttler3.getMinInterval()).isEqualTo(Duration.ofMillis(500));

        // 4. 全参工厂
        SftpProgressThrottler throttler4 = SftpProgressThrottler.of(dummy, 2.5, Duration.ofSeconds(1));
        assertThat(throttler4.getMinStepPercentage()).isEqualTo(2.5);
        assertThat(throttler4.getMinInterval()).isEqualTo(Duration.ofSeconds(1));

        // 5. 非法参数防卫拦截
        assertThatThrownBy(() -> SftpProgressThrottler.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SftpProgressThrottler.of(dummy, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SftpProgressThrottler.of(dummy, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SftpProgressThrottler.of(dummy, 101.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SftpProgressThrottler.of(dummy, 1.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SftpProgressThrottler.of(dummy, 1.0, Duration.ofMillis(-10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试每秒上万次高频回调防抖节流压制")
    void testHighFrequencyThrottling() {
        AtomicInteger callbackCount = new AtomicInteger(0);
        List<Long> transferredRecords = new ArrayList<>();

        SftpProgressListener listener = (transferred, total) -> {
            callbackCount.incrementAndGet();
            transferredRecords.add(transferred);
        };

        // 设置步长 1.0%，间隔 10 秒（在此测试中完全由 1% 步长控制）
        SftpProgressThrottler throttler = SftpProgressThrottler.of(listener, 1.0, Duration.ofSeconds(10));

        long totalBytes = 10_000_000L; // 10MB
        int totalIterations = 10_000;  // 模拟 10000 次高频回调
        long chunk = totalBytes / totalIterations; // 每次 1000 字节（0.01%）

        for (int i = 1; i <= totalIterations; i++) {
            throttler.onProgress(i * chunk, totalBytes);
        }

        // 10000 次调用中，只有每推进 1% 以及首次和末次 100% 会触发
        // 回调次数应在 100 次左右（100% 满额），绝不会触发 10000 次
        assertThat(callbackCount.get()).isLessThanOrEqualTo(105);
        assertThat(callbackCount.get()).isGreaterThanOrEqualTo(99);

        // 验证最后一次回调精确为 100% 满额
        assertThat(transferredRecords.get(transferredRecords.size() - 1)).isEqualTo(totalBytes);
        assertThat(throttler.isTerminalNotified()).isTrue();
    }

    @Test
    @DisplayName("测试基于时间窗口的防抖补偿")
    void testTimeWindowThrottling() throws InterruptedException {
        AtomicInteger callbackCount = new AtomicInteger(0);

        SftpProgressListener listener = (transferred, total) -> callbackCount.incrementAndGet();

        // 步长 50%，时间窗口 50ms
        SftpProgressThrottler throttler = SftpProgressThrottler.of(listener, 50.0, Duration.ofMillis(50));

        long totalBytes = 1_000_000L;

        // 首次回调
        throttler.onProgress(1000, totalBytes);
        assertThat(callbackCount.get()).isEqualTo(1);

        // 紧接着立即回调微小进度（步长未达 50%，时间未达 50ms，被节流抑制）
        throttler.onProgress(2000, totalBytes);
        throttler.onProgress(3000, totalBytes);
        assertThat(callbackCount.get()).isEqualTo(1);

        // 等待超过 50ms
        Thread.sleep(70);

        // 再次回调微小进度，时间窗口满足，成功触发
        throttler.onProgress(4000, totalBytes);
        assertThat(callbackCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试 100% 满额终态保底机制（即使步长和时间窗口未满足也必须触发）")
    void testGuaranteedTerminalEvent() {
        AtomicBoolean terminalTriggered = new AtomicBoolean(false);
        AtomicLong lastTransferred = new AtomicLong(0);

        SftpProgressListener listener = (transferred, total) -> {
            if (transferred == total) {
                terminalTriggered.set(true);
            }
            lastTransferred.set(transferred);
        };

        // 步长 50%，时间窗口 10 秒
        SftpProgressThrottler throttler = SftpProgressThrottler.of(listener, 50.0, Duration.ofSeconds(10));

        long totalBytes = 1000L;

        // 1. 首次触发 (100 字节，10%)
        throttler.onProgress(100, totalBytes);
        assertThat(throttler.getNotificationCount()).isEqualTo(1);

        // 2. 紧接着未达步长且未达时间窗口的进度 (120 字节)
        throttler.onProgress(120, totalBytes);
        assertThat(throttler.getNotificationCount()).isEqualTo(1);

        // 3. 终态满额推进至 1000 字节 (100%)，即使距上次仅几个纳秒且未达 50% 步长，必须强行触发 100% 终态
        throttler.onProgress(1000, totalBytes);
        assertThat(terminalTriggered.get()).isTrue();
        assertThat(lastTransferred.get()).isEqualTo(1000);
        assertThat(throttler.isTerminalNotified()).isTrue();

        // 4. 重复终态不重复触发
        long currentCount = throttler.getNotificationCount();
        throttler.onProgress(1000, totalBytes);
        assertThat(throttler.getNotificationCount()).isEqualTo(currentCount);

        // 5. close 闭合不重复触发
        throttler.close();
        assertThat(throttler.getNotificationCount()).isEqualTo(currentCount);
    }

    @Test
    @DisplayName("测试流闭合 close() 或 finish() 的终态保底")
    void testCloseAndFinishGuarantee() {
        AtomicLong finalTransferred = new AtomicLong(-1);
        AtomicLong finalTotal = new AtomicLong(-1);

        SftpProgressListener listener = (transferred, total) -> {
            finalTransferred.set(transferred);
            finalTotal.set(total);
        };

        // 未知总长度流 (total = -1)
        try (SftpProgressThrottler throttler = SftpProgressThrottler.of(listener, 5.0, Duration.ofSeconds(10))) {
            throttler.onProgress(500, -1);
            throttler.onProgress(600, -1);
            throttler.onProgress(750, -1);
        }

        // close() 执行后，应保底触发最后一次 750 字节的通知
        assertThat(finalTransferred.get()).isEqualTo(750);
        assertThat(finalTotal.get()).isEqualTo(-1);
    }

    @Test
    @DisplayName("测试 reset() 状态重置")
    void testReset() {
        AtomicInteger count = new AtomicInteger(0);
        SftpProgressThrottler throttler = SftpProgressThrottler.of((t, total) -> count.incrementAndGet());

        throttler.onProgress(100, 100);
        assertThat(throttler.isTerminalNotified()).isTrue();
        assertThat(throttler.getNotificationCount()).isEqualTo(1);

        throttler.reset();
        assertThat(throttler.isTerminalNotified()).isFalse();
        assertThat(throttler.getNotificationCount()).isEqualTo(0);
        assertThat(throttler.getLastNotifiedBytes()).isEqualTo(-1L);
    }
}
