package io.github.atengk.boot.ftp.listener;

import lombok.Getter;

/**
 * FTP 传输进度平滑防抖节流器
 * <p>
 * 内置步长阈值与时间间隔双轨防抖节流控制：
 * 仅当进度百分比增量达到步长阈值（默认 1.0%）或距离上次回调达到最小时间间隔（默认 200ms）时才触发实际通知；
 * 并在文件传输完整闭合时强制保证触发最后一次满额通知（100.0%），
 * 彻底消灭高吞吐传输下的无谓 CPU 争用、日志风暴与 UI 渲染卡顿。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class FtpProgressThrottler {

    public static final double DEFAULT_STEP_PERCENTAGE = 1.0;
    public static final long DEFAULT_MIN_INTERVAL_MS = 200L;

    private final FtpProgressListener listener;

    @Getter
    private final long totalBytes;

    @Getter
    private final double stepPercentage;

    @Getter
    private final long minIntervalMs;

    @Getter
    private long bytesTransferred;

    @Getter
    private double lastNotifiedPercentage = -1.0;

    @Getter
    private long lastNotifiedTime = 0L;

    @Getter
    private boolean completed = false;

    /**
     * 使用默认防抖参数（1% 步长，200ms 间隔）构建节流器
     *
     * @param listener   进度监听回调
     * @param totalBytes 目标文件总字节数（未知则传 -1）
     */
    public FtpProgressThrottler(FtpProgressListener listener, long totalBytes) {
        this(listener, totalBytes, DEFAULT_STEP_PERCENTAGE, DEFAULT_MIN_INTERVAL_MS);
    }

    /**
     * 全参构造器
     *
     * @param listener       进度监听回调
     * @param totalBytes     目标文件总字节数（未知则传 -1）
     * @param stepPercentage 百分比步长增量阈值（例如 1.0 表示 1%）
     * @param minIntervalMs  最小通知时间间隔毫秒数
     */
    public FtpProgressThrottler(FtpProgressListener listener, long totalBytes, double stepPercentage, long minIntervalMs) {
        this.listener = listener;
        this.totalBytes = totalBytes >= 0 ? totalBytes : -1L;
        this.stepPercentage = stepPercentage > 0 ? stepPercentage : DEFAULT_STEP_PERCENTAGE;
        this.minIntervalMs = minIntervalMs > 0 ? minIntervalMs : DEFAULT_MIN_INTERVAL_MS;
    }

    /**
     * 累加传输字节并按需触发防抖回调通知
     *
     * @param chunkBytes 当前数据块传输字节量
     */
    public synchronized void update(long chunkBytes) {
        if (chunkBytes <= 0 || listener == null || completed) {
            return;
        }
        this.bytesTransferred += chunkBytes;

        double currentPercentage = -1.0;
        if (totalBytes > 0) {
            currentPercentage = Math.min(100.0, (double) bytesTransferred / totalBytes * 100.0);
        } else if (totalBytes == 0) {
            currentPercentage = 100.0;
        }

        long now = System.currentTimeMillis();
        boolean shouldNotify = false;

        if (totalBytes >= 0) {
            // 首个包必定通知，或百分比增量满足步长，或达到最小时间间隔
            if (lastNotifiedPercentage < 0) {
                shouldNotify = true;
            } else if ((currentPercentage - lastNotifiedPercentage) >= stepPercentage) {
                shouldNotify = true;
            } else if ((now - lastNotifiedTime) >= minIntervalMs) {
                shouldNotify = true;
            }
        } else {
            // totalBytes 未知模式：基于时间间隔平滑通知
            if (lastNotifiedTime == 0L || (now - lastNotifiedTime) >= minIntervalMs) {
                shouldNotify = true;
            }
        }

        if (shouldNotify) {
            this.lastNotifiedPercentage = currentPercentage;
            this.lastNotifiedTime = now;
            listener.onProgress(bytesTransferred, totalBytes, currentPercentage);
        }
    }

    /**
     * 传输完成终态确认通知
     * <p>
     * 无论中间节流状态如何，强制保证触发最后一次 100.0% 满额通知，
     * 彻底杜绝进度条因步长截断而停留在 99.x% 的瑕疵。
     * </p>
     */
    public synchronized void finish() {
        if (completed || listener == null) {
            return;
        }
        this.completed = true;

        if (totalBytes >= 0) {
            long finalBytes = Math.max(bytesTransferred, totalBytes);
            // 只要尚未通知过满额 100.0%，即刻补发保底满额通知
            if (lastNotifiedPercentage < 100.0 || bytesTransferred < totalBytes) {
                this.lastNotifiedPercentage = 100.0;
                this.lastNotifiedTime = System.currentTimeMillis();
                listener.onProgress(finalBytes, totalBytes, 100.0);
            }
        } else {
            if (bytesTransferred > 0) {
                this.lastNotifiedTime = System.currentTimeMillis();
                listener.onProgress(bytesTransferred, totalBytes, -1.0);
            }
        }
    }
}
