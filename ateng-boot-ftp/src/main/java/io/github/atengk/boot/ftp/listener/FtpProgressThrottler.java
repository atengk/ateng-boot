package io.github.atengk.boot.ftp.listener;

import lombok.Getter;

import java.util.concurrent.locks.ReentrantLock;

/**
 * FTP 传输进度平滑防抖节流器
 * <p>
 * 内置步长阈值与时间间隔双轨防抖节流控制：
 * 仅当进度百分比增量达到步长阈值（默认 1.0%）或距离上次回调达到最小时间间隔（默认 200ms）时才触发实际通知；
 * 并在文件传输完整闭合时强制保证触发最后一次满额通知（100.0%），
 * 彻底消灭高吞吐传输下的无谓 CPU 争用、日志风暴与 UI 渲染卡顿。
 * 内部状态同步采用 {@link ReentrantLock} 并在释放锁后触发回调，杜绝 JDK 21+ 虚拟线程 Carrier Pinning。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class FtpProgressThrottler {

    public static final double DEFAULT_STEP_PERCENTAGE = 1.0;
    public static final long DEFAULT_MIN_INTERVAL_MS = 200L;

    private final ReentrantLock lock = new ReentrantLock();
    private final FtpProgressListener listener;

    @Getter
    private final long totalBytes;

    @Getter
    private final double stepPercentage;

    @Getter
    private final long minIntervalMs;

    @Getter
    private volatile long bytesTransferred;

    @Getter
    private volatile double lastNotifiedPercentage = -1.0;

    @Getter
    private volatile long lastNotifiedTime = 0L;

    @Getter
    private volatile boolean completed = false;

    /**
     * 使用默认防抖参数（1% 步长，200ms 间隔）构建节流器
     *
     * @param listener   进度监听回调
     * @param totalBytes 目标文件总字节数（未知则传 -1）
     */
    public FtpProgressThrottler(FtpProgressListener listener, long totalBytes) {
        this(listener, totalBytes, 0L, DEFAULT_STEP_PERCENTAGE, DEFAULT_MIN_INTERVAL_MS);
    }

    /**
     * 使用指定初始断点偏移量与默认防抖参数构建节流器（适用于断点续传场景）
     *
     * @param listener                进度监听回调
     * @param totalBytes              目标文件总字节数（未知则传 -1）
     * @param initialBytesTransferred 初始已传输字节偏移量
     */
    public FtpProgressThrottler(FtpProgressListener listener, long totalBytes, long initialBytesTransferred) {
        this(listener, totalBytes, initialBytesTransferred, DEFAULT_STEP_PERCENTAGE, DEFAULT_MIN_INTERVAL_MS);
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
        this(listener, totalBytes, 0L, stepPercentage, minIntervalMs);
    }

    /**
     * 含初始断点偏移量的全参构造器
     *
     * @param listener                进度监听回调
     * @param totalBytes              目标文件总字节数（未知则传 -1）
     * @param initialBytesTransferred 初始已传输字节偏移量
     * @param stepPercentage          百分比步长增量阈值
     * @param minIntervalMs           最小通知时间间隔毫秒数
     */
    public FtpProgressThrottler(FtpProgressListener listener, long totalBytes, long initialBytesTransferred,
                                double stepPercentage, long minIntervalMs) {
        this.listener = listener;
        this.totalBytes = totalBytes >= 0 ? totalBytes : -1L;
        this.bytesTransferred = Math.max(0L, initialBytesTransferred);
        this.stepPercentage = stepPercentage > 0 ? stepPercentage : DEFAULT_STEP_PERCENTAGE;
        this.minIntervalMs = minIntervalMs > 0 ? minIntervalMs : DEFAULT_MIN_INTERVAL_MS;
    }

    /**
     * 累加传输字节并按需触发防抖回调通知（锁内更新状态快照，锁外派发回调防 Pinning）
     *
     * @param chunkBytes 当前数据块传输字节量
     */
    public void update(long chunkBytes) {
        if (chunkBytes <= 0 || listener == null) {
            return;
        }

        boolean shouldNotify = false;
        long snapshotBytes = 0L;
        double snapshotPercentage = -1.0;

        lock.lock();
        try {
            if (completed) {
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
                snapshotBytes = this.bytesTransferred;
                snapshotPercentage = currentPercentage;
            }
        } finally {
            lock.unlock();
        }

        if (shouldNotify) {
            listener.onProgress(snapshotBytes, totalBytes, snapshotPercentage);
        }
    }

    /**
     * 传输完成终态确认通知
     * <p>
     * 无论中间节流状态如何，强制保证触发最后一次 100.0% 满额通知，
     * 彻底杜绝进度条因步长截断而停留在 99.x% 的瑕疵。
     * </p>
     */
    public void finish() {
        if (listener == null) {
            return;
        }

        boolean shouldNotify = false;
        long finalBytes = 0L;
        double finalPercentage = -1.0;

        lock.lock();
        try {
            if (completed) {
                return;
            }
            this.completed = true;

            if (totalBytes >= 0) {
                finalBytes = Math.max(bytesTransferred, totalBytes);
                // 只要尚未通知过满额 100.0%，即刻补发保底满额通知
                if (lastNotifiedPercentage < 100.0 || bytesTransferred < totalBytes) {
                    this.lastNotifiedPercentage = 100.0;
                    this.lastNotifiedTime = System.currentTimeMillis();
                    finalPercentage = 100.0;
                    shouldNotify = true;
                }
            } else if (bytesTransferred > 0) {
                this.lastNotifiedTime = System.currentTimeMillis();
                finalBytes = this.bytesTransferred;
                finalPercentage = -1.0;
                shouldNotify = true;
            }
        } finally {
            lock.unlock();
        }

        if (shouldNotify) {
            listener.onProgress(finalBytes, totalBytes, finalPercentage);
        }
    }
}
