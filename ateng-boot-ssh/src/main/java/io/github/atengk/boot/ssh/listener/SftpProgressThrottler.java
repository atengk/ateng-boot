package io.github.atengk.boot.ssh.listener;

import org.springframework.util.Assert;

import java.time.Duration;

/**
 * SFTP 流式文件传输进度平滑节流器
 * <p>
 * 采用受管装饰器模式，默认注入双重阈值防抖（步长阈值 1% 与时间窗口 200ms），
 * 杜绝高速大文件传输（如千兆内网、持续高频流式读写）每传输 32KB 即频繁回调一次所引发的 UI 渲染卡顿与事件风暴。
 * 传输结束（{@code transferredBytes >= totalBytes}）或流闭合时，100% 保底触发满额终态通知。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SftpProgressThrottler implements SftpProgressListener, AutoCloseable {

    /**
     * 默认步长阈值：1.0%
     */
    public static final double DEFAULT_STEP_PERCENTAGE = 1.0;

    /**
     * 默认时间窗口阈值：200 毫秒
     */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(200);

    private final SftpProgressListener delegate;
    private final double minStepPercentage;
    private final Duration minInterval;
    private final long minIntervalMillis;

    private long lastNotifiedBytes = -1L;
    private long lastNotifiedTime = 0L;
    private double lastNotifiedPercentage = -1.0;
    private long lastTransferredBytes = 0L;
    private long lastTotalBytes = -1L;
    private long notificationCount = 0L;
    private boolean terminalNotified = false;

    /**
     * 构造默认双重阈值（步长 1.0%，间隔 200ms）的流式进度节流器
     *
     * @param delegate 被包装的原始进度监听器
     */
    public SftpProgressThrottler(SftpProgressListener delegate) {
        this(delegate, DEFAULT_STEP_PERCENTAGE, DEFAULT_INTERVAL);
    }

    /**
     * 构造指定步长与时间间隔的流式进度节流器
     *
     * @param delegate          被包装的原始进度监听器
     * @param minStepPercentage 最小进度百分比推进步长（取值范围 (0, 100]）
     * @param minInterval       最小回调时间间隔（严禁为 null 且不可为负数）
     */
    public SftpProgressThrottler(SftpProgressListener delegate, double minStepPercentage, Duration minInterval) {
        Assert.notNull(delegate, "Delegate SftpProgressListener must not be null");
        Assert.isTrue(minStepPercentage > 0.0 && minStepPercentage <= 100.0, "minStepPercentage must be in range (0, 100]");
        Assert.notNull(minInterval, "minInterval must not be null");
        Assert.isTrue(!minInterval.isNegative(), "minInterval must not be negative");

        this.delegate = delegate;
        this.minStepPercentage = minStepPercentage;
        this.minInterval = minInterval;
        this.minIntervalMillis = minInterval.toMillis();
    }

    /**
     * 便捷工厂方法：构造默认双重阈值（步长 1.0%，间隔 200ms）的节流器
     *
     * @param delegate 被包装的原始进度监听器
     * @return 进度节流器实例
     */
    public static SftpProgressThrottler of(SftpProgressListener delegate) {
        return new SftpProgressThrottler(delegate);
    }

    /**
     * 便捷工厂方法：构造指定最小推进百分比步长（默认 200ms 间隔）的节流器
     *
     * @param delegate          被包装的原始进度监听器
     * @param minStepPercentage 最小进度百分比步长
     * @return 进度节流器实例
     */
    public static SftpProgressThrottler of(SftpProgressListener delegate, double minStepPercentage) {
        return new SftpProgressThrottler(delegate, minStepPercentage, DEFAULT_INTERVAL);
    }

    /**
     * 便捷工厂方法：构造指定最小回调时间间隔（默认 1.0% 步长）的节流器
     *
     * @param delegate    被包装的原始进度监听器
     * @param minInterval 最小回调时间间隔
     * @return 进度节流器实例
     */
    public static SftpProgressThrottler of(SftpProgressListener delegate, Duration minInterval) {
        return new SftpProgressThrottler(delegate, DEFAULT_STEP_PERCENTAGE, minInterval);
    }

    /**
     * 便捷工厂方法：构造指定步长与时间间隔的节流器
     *
     * @param delegate          被包装的原始进度监听器
     * @param minStepPercentage 最小进度百分比步长
     * @param minInterval       最小回调时间间隔
     * @return 进度节流器实例
     */
    public static SftpProgressThrottler of(SftpProgressListener delegate, double minStepPercentage, Duration minInterval) {
        return new SftpProgressThrottler(delegate, minStepPercentage, minInterval);
    }

    @Override
    public synchronized void onProgress(long transferredBytes, long totalBytes) {
        if (delegate == null) {
            return;
        }
        this.lastTransferredBytes = transferredBytes;
        this.lastTotalBytes = totalBytes;

        // 1. 终态满额保底拦截：若已传输达到或超过总大小（且总大小已知 >= 0），无条件触发 100% 终态通知
        if (totalBytes >= 0 && transferredBytes >= totalBytes) {
            if (!terminalNotified) {
                notifyDelegate(transferredBytes, totalBytes, 100.0);
                this.terminalNotified = true;
            }
            return;
        }

        long now = System.currentTimeMillis();

        // 2. 首次回调触发（建立基线）
        if (lastNotifiedBytes < 0) {
            double initialPercentage = (totalBytes > 0) ? (transferredBytes * 100.0 / totalBytes) : -1.0;
            notifyDelegate(transferredBytes, totalBytes, initialPercentage);
            return;
        }

        // 3. 双重防抖条件判定：时间间隔满足 OR 进度步长满足
        boolean timeExceeded = (now - lastNotifiedTime >= minIntervalMillis);
        boolean stepExceeded = false;
        double currentPercentage = -1.0;

        if (totalBytes > 0) {
            currentPercentage = transferredBytes * 100.0 / totalBytes;
            if (currentPercentage - lastNotifiedPercentage >= minStepPercentage) {
                stepExceeded = true;
            }
        }

        if (timeExceeded || stepExceeded) {
            notifyDelegate(transferredBytes, totalBytes, currentPercentage);
        }
    }

    /**
     * 触发满额终态保底通知并闭合状态
     */
    public synchronized void finish() {
        if (!terminalNotified && delegate != null) {
            if (lastTotalBytes >= 0 && lastTransferredBytes >= lastTotalBytes) {
                notifyDelegate(lastTotalBytes, lastTotalBytes, 100.0);
            } else if (lastTotalBytes < 0 && lastTransferredBytes > 0 && lastTransferredBytes != lastNotifiedBytes) {
                notifyDelegate(lastTransferredBytes, lastTotalBytes, -1.0);
            }
            this.terminalNotified = true;
        }
    }

    @Override
    public void close() {
        finish();
    }

    /**
     * 重置节流器状态
     */
    public synchronized void reset() {
        this.lastNotifiedBytes = -1L;
        this.lastNotifiedTime = 0L;
        this.lastNotifiedPercentage = -1.0;
        this.lastTransferredBytes = 0L;
        this.lastTotalBytes = -1L;
        this.notificationCount = 0L;
        this.terminalNotified = false;
    }

    private void notifyDelegate(long transferredBytes, long totalBytes, double percentage) {
        this.lastNotifiedBytes = transferredBytes;
        this.lastNotifiedTime = System.currentTimeMillis();
        this.lastNotifiedPercentage = percentage;
        this.notificationCount++;
        delegate.onProgress(transferredBytes, totalBytes);
    }

    public SftpProgressListener getDelegate() {
        return delegate;
    }

    public double getMinStepPercentage() {
        return minStepPercentage;
    }

    public Duration getMinInterval() {
        return minInterval;
    }

    public synchronized long getLastNotifiedBytes() {
        return lastNotifiedBytes;
    }

    public synchronized double getLastNotifiedPercentage() {
        return lastNotifiedPercentage;
    }

    public synchronized long getNotificationCount() {
        return notificationCount;
    }

    public synchronized boolean isTerminalNotified() {
        return terminalNotified;
    }
}
