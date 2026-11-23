package io.github.atengk.boot.ftp.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FtpProgressListener 与 FtpProgressThrottler 节流防抖与终态保底单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("FtpProgressListener 进度监听与节流算法单元测试")
class FtpProgressListenerTest {

    record ProgressSnapshot(long bytesTransferred, long totalBytes, double percentage) {}

    @Test
    @DisplayName("测试进度节流器有效防抖（避免高频微小步长重复回调）")
    void testProgressThrottlingSuppression() {
        List<ProgressSnapshot> snapshots = new ArrayList<>();
        FtpProgressListener listener = (transferred, total, percentage) ->
                snapshots.add(new ProgressSnapshot(transferred, total, percentage));

        long totalBytes = 100_000L;
        // 步长 1.0%，最小间隔 500ms
        FtpProgressThrottler throttler = new FtpProgressThrottler(listener, totalBytes, 1.0, 500L);

        // 每次写入 10 字节 (0.01%)，连续写 50 次 (累计 500 字节，0.5%)
        for (int i = 0; i < 50; i++) {
            throttler.update(10);
        }

        // 第一个包触发首发通知，后续未达到 1% 也未达到 500ms，应当被严格节流
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).bytesTransferred()).isEqualTo(10L);

        // 写入一次大增量 1,000 字节 (1.0%)
        throttler.update(1_000);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(1).percentage()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("测试传输完成时 100% 强制满额终态保底通知")
    void testGuaranteedTerminalProgressNotification() {
        List<ProgressSnapshot> snapshots = new ArrayList<>();
        FtpProgressListener listener = (transferred, total, percentage) ->
                snapshots.add(new ProgressSnapshot(transferred, total, percentage));

        long totalBytes = 10_000L;
        FtpProgressThrottler throttler = new FtpProgressThrottler(listener, totalBytes, 5.0, 500L);

        // 传输到 9,800 字节 (98.0%)
        throttler.update(9_800);

        // 此时由于未到 100% 且可能停在 98%
        assertThat(snapshots.get(snapshots.size() - 1).percentage()).isLessThan(100.0);

        // 调用 finish() 模拟流正常闭合传输完毕
        throttler.finish();

        // 断言最后一次通知必须是 100.0%，且已传输字节等于总字节数
        ProgressSnapshot lastSnapshot = snapshots.get(snapshots.size() - 1);
        assertThat(lastSnapshot.percentage()).isEqualTo(100.0);
        assertThat(lastSnapshot.bytesTransferred()).isEqualTo(totalBytes);
        assertThat(lastSnapshot.totalBytes()).isEqualTo(totalBytes);
        assertThat(throttler.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("测试未知总大小 (totalBytes = -1) 模式平滑通知")
    void testUnknownTotalBytesMode() {
        List<ProgressSnapshot> snapshots = new ArrayList<>();
        FtpProgressListener listener = (transferred, total, percentage) ->
                snapshots.add(new ProgressSnapshot(transferred, total, percentage));

        FtpProgressThrottler throttler = new FtpProgressThrottler(listener, -1L, 1.0, 200L);

        throttler.update(512);
        throttler.finish();

        assertThat(snapshots).isNotEmpty();
        for (ProgressSnapshot snapshot : snapshots) {
            assertThat(snapshot.totalBytes()).isEqualTo(-1L);
            assertThat(snapshot.percentage()).isEqualTo(-1.0);
        }
        assertThat(snapshots.get(snapshots.size() - 1).bytesTransferred()).isEqualTo(512L);
    }

    @Test
    @DisplayName("测试空监听器与非法增量防御安全")
    void testNullListenerAndNegativeBytesDefensive() {
        FtpProgressThrottler throttler = new FtpProgressThrottler(null, 100L);
        // 不应抛出任何异常
        throttler.update(10);
        throttler.update(-5);
        throttler.finish();

        AtomicBoolean notified = new AtomicBoolean(false);
        FtpProgressThrottler validThrottler = new FtpProgressThrottler((b, t, p) -> notified.set(true), 100L);
        validThrottler.update(-10);
        assertThat(notified.get()).isFalse();
    }

    @Test
    @DisplayName("测试 0 字节空文件终态 100% 满额保底通知")
    void testZeroByteFileTerminalNotification() {
        List<ProgressSnapshot> snapshots = new ArrayList<>();
        FtpProgressListener listener = (transferred, total, percentage) ->
                snapshots.add(new ProgressSnapshot(transferred, total, percentage));

        FtpProgressThrottler throttler = new FtpProgressThrottler(listener, 0L);
        throttler.finish();

        assertThat(snapshots).hasSize(1);
        ProgressSnapshot snapshot = snapshots.get(0);
        assertThat(snapshot.bytesTransferred()).isEqualTo(0L);
        assertThat(snapshot.totalBytes()).isEqualTo(0L);
        assertThat(snapshot.percentage()).isEqualTo(100.0);
        assertThat(throttler.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("测试初始断点偏移量构造器从断点百分比平滑递增至 100%")
    void testInitialOffsetResumableProgress() {
        List<ProgressSnapshot> snapshots = new ArrayList<>();
        FtpProgressListener listener = (transferred, total, percentage) ->
                snapshots.add(new ProgressSnapshot(transferred, total, percentage));

        FtpProgressThrottler throttler = new FtpProgressThrottler(listener, 1_000L, 500L);
        throttler.update(100L);
        throttler.finish();

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).bytesTransferred()).isEqualTo(600L);
        assertThat(snapshots.get(0).percentage()).isEqualTo(60.0);
        assertThat(snapshots.get(1).bytesTransferred()).isEqualTo(1_000L);
        assertThat(snapshots.get(1).percentage()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("测试锁外触发回调且不持有对象监视器锁 (防虚拟线程 Pinning 与跨线程重入死锁)")
    void testCallbackInvokedOutsideLockWithoutMonitorPinning() throws Exception {
        List<ProgressSnapshot> snapshots = new CopyOnWriteArrayList<>();
        AtomicBoolean holdsMonitorLock = new AtomicBoolean(false);
        AtomicReference<FtpProgressThrottler> throttlerRef = new AtomicReference<>();
        AtomicBoolean concurrentUpdateTriggered = new AtomicBoolean(false);

        FtpProgressListener listener = (transferred, total, percentage) -> {
            snapshots.add(new ProgressSnapshot(transferred, total, percentage));
            if (Thread.holdsLock(throttlerRef.get())) {
                holdsMonitorLock.set(true);
            }
            if (concurrentUpdateTriggered.compareAndSet(false, true)) {
                // 若在回调期间仍持有互斥锁，则另一线程调用 update 将死锁超限
                CompletableFuture<Void> otherThreadUpdate = CompletableFuture.runAsync(
                        () -> throttlerRef.get().update(200L));
                try {
                    otherThreadUpdate.get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("回调期间发生锁争用死锁", e);
                }
            }
        };

        FtpProgressThrottler throttler = new FtpProgressThrottler(listener, 1_000L, 1.0, 200L);
        throttlerRef.set(throttler);

        throttler.update(100L);
        throttler.finish();

        assertThat(holdsMonitorLock.get()).isFalse();
        assertThat(throttler.getBytesTransferred()).isEqualTo(300L);
        assertThat(snapshots.get(snapshots.size() - 1).percentage()).isEqualTo(100.0);
    }
}
