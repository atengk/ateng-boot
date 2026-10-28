package io.github.atengk.boot.core.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 纯 JDK 17 原生基于原子 CAS 与纳秒级时间推进的高性能无锁平滑令牌桶限流器
 * 具备突发流量缓冲、平滑速率整形与非阻塞/超时限流获取能力，零外部中间件依赖、零多余后台守护线程
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class TokenBucketLimiter {

    private final long capacity;
    private final double refillTokensPerSecond;
    private final AtomicReference<State> stateRef;

    private static final class State {
        final double tokens;
        final long lastRefillNanos;

        State(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    /**
     * 构造平滑令牌桶限流器（初始容量默认为满桶）
     *
     * @param capacity              桶最大容量（允许突发流量的最大令牌数，必须 > 0）
     * @param refillTokensPerSecond 每秒向桶中补充的令牌速率（必须 > 0）
     */
    public TokenBucketLimiter(long capacity, double refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, (double) capacity);
    }

    /**
     * 构造平滑令牌桶限流器（支持自定义初始令牌数）
     *
     * @param capacity              桶最大容量（必须 > 0）
     * @param refillTokensPerSecond 每秒补充的令牌速率（必须 > 0）
     * @param initialTokens         初始可用令牌数（0 <= initialTokens <= capacity）
     */
    public TokenBucketLimiter(long capacity, double refillTokensPerSecond, double initialTokens) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("桶容量 capacity 必须大于 0: " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("每秒填充速率 refillTokensPerSecond 必须大于 0: " + refillTokensPerSecond);
        }
        if (initialTokens < 0) {
            throw new IllegalArgumentException("初始令牌数 initialTokens 不能小于 0: " + initialTokens);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        double init = Math.min((double) capacity, initialTokens);
        this.stateRef = new AtomicReference<>(new State(init, System.nanoTime()));
    }

    /**
     * 尝试获取 1 个令牌（非阻塞立即返回）
     *
     * @return 若成功获取返回 true；若当前令牌不足返回 false
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取指定数量的令牌（非阻塞立即返回）
     *
     * @param permits 请求获取的令牌数（必须 > 0）
     * @return 若成功获取返回 true；若当前令牌不足返回 false
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("请求令牌数 permits 必须大于 0: " + permits);
        }
        if (permits > capacity) {
            return false;
        }

        while (true) {
            State current = stateRef.get();
            long now = System.nanoTime();
            long elapsedNanos = Math.max(0L, now - current.lastRefillNanos);
            double delta = (elapsedNanos * refillTokensPerSecond) / 1_000_000_000.0;
            double available = Math.min((double) capacity, current.tokens + delta);

            if (available < permits) {
                return false;
            }

            State next = new State(available - permits, now);
            if (stateRef.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * 尝试在指定超时时间内等待并获取指定数量令牌
     *
     * @param permits 请求获取的令牌数
     * @param timeout 最大等待超时时间 Duration
     * @return 在超时时间内成功获取返回 true；若超时返回 false
     */
    public boolean tryAcquire(int permits, Duration timeout) {
        Objects.requireNonNull(timeout, "超时时间 Duration 不能为 null");
        return tryAcquire(permits, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 尝试在指定超时时间内等待并获取指定数量令牌
     *
     * @param permits 请求获取的令牌数
     * @param timeout 超时数值
     * @param unit    时间单位
     * @return 在超时时间内成功获取返回 true；若超时或被中断返回 false
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
        if (permits <= 0) {
            throw new IllegalArgumentException("请求令牌数 permits 必须大于 0: " + permits);
        }
        Objects.requireNonNull(unit, "时间单位 TimeUnit 不能为 null");
        if (permits > capacity) {
            return false;
        }

        if (tryAcquire(permits)) {
            return true;
        }

        long timeoutNanos = unit.toNanos(timeout);
        if (timeoutNanos <= 0) {
            return false;
        }

        long deadline = System.nanoTime() + timeoutNanos;

        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }

            State current = stateRef.get();
            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - current.lastRefillNanos);
            double available = Math.min((double) capacity, current.tokens + (elapsed * refillTokensPerSecond) / 1_000_000_000.0);

            if (available >= permits) {
                if (tryAcquire(permits)) {
                    return true;
                }
                continue;
            }

            double missing = permits - available;
            long waitNanos = (long) Math.ceil((missing / refillTokensPerSecond) * 1_000_000_000.0);
            if (waitNanos > remaining) {
                return false;
            }

            long sleepNanos = Math.min(waitNanos, remaining);
            if (sleepNanos > 0) {
                LockSupport.parkNanos(sleepNanos);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /**
     * 阻塞获取 1 个令牌（直到获取成功或线程被中断）
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * 阻塞获取指定数量令牌（直到获取成功或线程被中断）
     *
     * @param permits 请求获取的令牌数
     */
    public void acquire(int permits) {
        if (permits > capacity) {
            throw new IllegalArgumentException("请求令牌数 permits [" + permits + "] 不能超过桶容量 capacity [" + capacity + "]");
        }
        while (!tryAcquire(permits, 1, TimeUnit.SECONDS)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("当前线程已被中断，终止 acquire 阻塞等待");
            }
        }
    }

    /**
     * 获取当前桶中估算的可用令牌数
     *
     * @return 可用令牌数
     */
    public double getAvailableTokens() {
        State current = stateRef.get();
        long now = System.nanoTime();
        long elapsedNanos = Math.max(0L, now - current.lastRefillNanos);
        double delta = (elapsedNanos * refillTokensPerSecond) / 1_000_000_000.0;
        return Math.min((double) capacity, current.tokens + delta);
    }

    /**
     * 获取桶的最大容量
     *
     * @return 桶容量
     */
    public long getCapacity() {
        return capacity;
    }

    /**
     * 获取每秒填充令牌速率
     *
     * @return 每秒填充速率
     */
    public double getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    /**
     * 重置令牌桶为初始满桶状态
     */
    public void reset() {
        stateRef.set(new State((double) capacity, System.nanoTime()));
    }
}
