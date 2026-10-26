package io.github.atengk.boot.core.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 高性能单机内存滑动窗口计数器
 * 基于环形时间切片（Ring Slices）与原子累加器实现，无需依赖 Redis 等外部组件即可实现进程内精准平滑限流、防刷与告警去重
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class SlidingWindowCounter {

    private final long windowMillis;
    private final int sliceCount;
    private final long sliceMillis;
    private final Slice[] slices;
    private final Object mutex = new Object();

    /**
     * 构造默认滑动窗口计数器（默认窗口 1 秒，切分为 10 个切片，每个切片 100 毫秒）
     */
    public SlidingWindowCounter() {
        this(Duration.ofSeconds(1), 10);
    }

    /**
     * 自定义窗口时长与切片数量构造滑动窗口计数器
     *
     * @param windowDuration 窗口总时长
     * @param sliceCount     切片数量（必须大于 0）
     * @throws IllegalArgumentException 当参数非法时抛出
     */
    public SlidingWindowCounter(Duration windowDuration, int sliceCount) {
        Objects.requireNonNull(windowDuration, "windowDuration 不能为空");
        if (windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("windowDuration 必须大于 0");
        }
        if (sliceCount <= 0) {
            throw new IllegalArgumentException("sliceCount 必须大于 0");
        }

        this.windowMillis = windowDuration.toMillis();
        this.sliceCount = sliceCount;
        this.sliceMillis = Math.max(1, this.windowMillis / sliceCount);

        this.slices = new Slice[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            this.slices[i] = new Slice();
        }
    }

    /**
     * 窗口计数器自增 1 并返回当前窗口有效累计总数
     *
     * @return 当前滑动窗口内的新计数值
     */
    public long incrementAndGet() {
        return incrementAndGet(1);
    }

    /**
     * 窗口计数器原子递增指定增量并返回当前窗口有效累计总数
     *
     * @param delta 增加的计数值（必须大于 0）
     * @return 当前滑动窗口内的新计数值
     * @throws IllegalArgumentException 当 delta <= 0 时抛出
     */
    public long incrementAndGet(long delta) {
        if (delta <= 0) {
            throw new IllegalArgumentException("delta 必须大于 0");
        }
        long now = System.currentTimeMillis();
        Slice slice = locateSlice(now);
        slice.add(delta);
        return getCount(now);
    }

    /**
     * 尝试获取许可（限流门面）
     * 当窗口内当前计数值加上 1 未超过阈值时允许放行并原子增加计数
     *
     * @param threshold 限流阈值
     * @return 若未超限则返回 true，否则返回 false
     */
    public boolean tryAcquire(long threshold) {
        return tryAcquire(1, threshold);
    }

    /**
     * 尝试获取指定数量许可（限流门面）
     *
     * @param delta     请求的许可数
     * @param threshold 限流阈值
     * @return 若未超限则返回 true，否则返回 false
     * @throws IllegalArgumentException 当 delta <= 0 时抛出
     */
    public boolean tryAcquire(long delta, long threshold) {
        if (delta <= 0) {
            throw new IllegalArgumentException("delta 必须大于 0");
        }
        synchronized (mutex) {
            long now = System.currentTimeMillis();
            long currentCount = getCount(now);
            if (currentCount + delta <= threshold) {
                Slice slice = locateSlice(now);
                slice.add(delta);
                return true;
            }
            return false;
        }
    }

    /**
     * 获取当前滑动窗口内的有效累计总数
     *
     * @return 当前窗口有效计数
     */
    public long getCount() {
        return getCount(System.currentTimeMillis());
    }

    /**
     * 重置清空所有切片计数
     */
    public void reset() {
        synchronized (mutex) {
            for (Slice slice : slices) {
                slice.reset(0);
            }
        }
    }

    private Slice locateSlice(long now) {
        long bucketTime = now - (now % sliceMillis);
        int index = (int) Math.floorMod(now / sliceMillis, sliceCount);
        Slice slice = slices[index];
        if (slice.timestamp.get() != bucketTime) {
            synchronized (slice) {
                if (slice.timestamp.get() != bucketTime) {
                    slice.reset(bucketTime);
                }
            }
        }
        return slice;
    }

    private long getCount(long now) {
        long sum = 0;
        for (Slice slice : slices) {
            long time = slice.timestamp.get();
            // 处于 [now - windowMillis, now] 范围内的切片属于有效窗口数据
            if (time > 0 && (now - time) < windowMillis && (now - time) >= 0) {
                sum += slice.counter.sum();
            }
        }
        return sum;
    }

    private static class Slice {
        private final AtomicLong timestamp = new AtomicLong(0);
        private final LongAdder counter = new LongAdder();

        void add(long delta) {
            counter.add(delta);
        }

        void reset(long newTimestamp) {
            timestamp.set(newTimestamp);
            counter.reset();
        }
    }
}
