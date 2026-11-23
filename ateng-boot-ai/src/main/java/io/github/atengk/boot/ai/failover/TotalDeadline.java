package io.github.atengk.boot.ai.failover;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;

import java.time.Duration;

/**
 * 全局时延预算 (Total Deadline Budget) 上下文
 * <p>
 * 在主备模型故障转移与跨网络重试链路中共享统一的倒计时时钟，
 * 当全局时延预算耗尽时立即阻断后续重试或转移，防御上游网关级超时雪崩。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class TotalDeadline {

    private final Duration totalTimeout;
    private final long deadlineNanos;
    private final boolean infinite;

    private TotalDeadline(Duration totalTimeout) {
        this.totalTimeout = totalTimeout;
        if (totalTimeout != null && !totalTimeout.isZero() && !totalTimeout.isNegative()) {
            this.deadlineNanos = System.nanoTime() + totalTimeout.toNanos();
            this.infinite = false;
        } else {
            this.deadlineNanos = Long.MAX_VALUE;
            this.infinite = true;
        }
    }

    /**
     * 创建基于指定时长的 TotalDeadline 实例
     *
     * @param totalTimeout 总时延预算 (若为 null 或非正数则视为无时延预算限制)
     * @return TotalDeadline 实例
     */
    public static TotalDeadline of(Duration totalTimeout) {
        return new TotalDeadline(totalTimeout);
    }

    /**
     * 创建无限制的时延预算实例
     *
     * @return TotalDeadline 实例
     */
    public static TotalDeadline infinite() {
        return new TotalDeadline(null);
    }

    /**
     * 判断当前时延预算是否已耗尽超时
     *
     * @return 若已超时返回 true，否则返回 false
     */
    public boolean isExpired() {
        if (infinite) {
            return false;
        }
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * 获取剩余时延预算时长
     *
     * @return 剩余 Duration (若已超时则返回 Duration.ZERO)
     */
    public Duration getRemaining() {
        if (infinite) {
            return Duration.ofSeconds(Integer.MAX_VALUE);
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos > 0 ? Duration.ofNanos(remainingNanos) : Duration.ZERO;
    }

    /**
     * 获取剩余时延预算毫秒数
     *
     * @return 剩余毫秒数 (若已超时则返回 0)
     */
    public long getRemainingMillis() {
        return Math.max(0, getRemaining().toMillis());
    }

    /**
     * 校验全局时延预算是否未耗尽；若已耗尽则立即抛出 AI_TIMEOUT 业务异常
     *
     * @throws AiException 当时延预算耗尽时抛出
     */
    public void checkNotExpired() {
        checkNotExpired("当前操作");
    }

    /**
     * 校验全局时延预算是否未耗尽；若已耗尽则立即抛出包含操作描述的 AI_TIMEOUT 业务异常
     *
     * @param operation 正在执行的操作或模型描述
     * @throws AiException 当时延预算耗尽时抛出
     */
    public void checkNotExpired(String operation) {
        if (isExpired()) {
            throw new AiException(AiResultCode.AI_TIMEOUT,
                    String.format("全局时延预算已耗尽 (Total Deadline 超时: %s)，中止执行 [%s]",
                            totalTimeout != null ? totalTimeout : "unlimited", operation));
        }
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public boolean isInfinite() {
        return infinite;
    }
}
