package io.github.atengk.boot.realtime.ratelimit;

import io.github.atengk.boot.realtime.properties.RealtimeProperties.RateLimitProperties;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 长连接会话级内存无锁滑动窗口限流器
 * <p>
 * 基于无锁原子时间戳与双窗口滑动计数算法（Weighted Sliding Window），
 * 在单个会话内部执行纳秒级频控，杜绝 Redis 网络跳跃开销。
 * 支持两阶梯惩罚治理：
 * <ul>
 *     <li>第 1 阶：超出每秒限额时返回 WARNING，丢弃当前请求并告警；</li>
 *     <li>第 2 阶：连续多次超限达到阈值时返回 CLOSE，触发服务端强制切断连接。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SessionRateLimiter {

    @Getter
    private final int maxRequestsPerSecond;
    @Getter
    private final int consecutiveViolationsBeforeClose;
    private final LongSupplier timeSupplier;

    // 滑动窗口状态
    private final AtomicLong currentWindowSecond = new AtomicLong(0);
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicInteger previousCount = new AtomicInteger(0);

    // 连续违规计数
    private final AtomicInteger consecutiveViolations = new AtomicInteger(0);

    public SessionRateLimiter(RateLimitProperties properties) {
        this(properties != null ? properties.getMaxRequestsPerSecond() : 50,
                properties != null ? properties.getConsecutiveViolationsBeforeClose() : 3,
                System::currentTimeMillis);
    }

    public SessionRateLimiter(int maxRequestsPerSecond, int consecutiveViolationsBeforeClose) {
        this(maxRequestsPerSecond, consecutiveViolationsBeforeClose, System::currentTimeMillis);
    }

    public SessionRateLimiter(int maxRequestsPerSecond, int consecutiveViolationsBeforeClose, LongSupplier timeSupplier) {
        this.maxRequestsPerSecond = Math.max(1, maxRequestsPerSecond);
        this.consecutiveViolationsBeforeClose = Math.max(0, consecutiveViolationsBeforeClose);
        this.timeSupplier = timeSupplier != null ? timeSupplier : System::currentTimeMillis;
    }

    /**
     * 尝试获取上行访问许可并计算决策
     *
     * @return 限流决策结果 (ALLOWED / WARNING / CLOSE)
     */
    public RateLimitDecision tryAcquire() {
        long now = timeSupplier.getAsLong();
        long second = now / 1000;
        long lastSecond = currentWindowSecond.get();

        if (second != lastSecond) {
            if (currentWindowSecond.compareAndSet(lastSecond, second)) {
                if (second == lastSecond + 1) {
                    previousCount.set(currentCount.get());
                } else {
                    previousCount.set(0);
                }
                currentCount.set(0);
            }
        }

        int countInCurrentWindow = currentCount.incrementAndGet();
        long millisInCurrentSecond = now % 1000;
        double weight = (1000.0 - millisInCurrentSecond) / 1000.0;
        double estimatedRequests = previousCount.get() * weight + countInCurrentWindow;

        if (estimatedRequests <= maxRequestsPerSecond) {
            consecutiveViolations.set(0);
            return RateLimitDecision.ALLOWED;
        }

        // 超出阈值
        int violations = consecutiveViolations.incrementAndGet();
        if (consecutiveViolationsBeforeClose > 0 && violations >= consecutiveViolationsBeforeClose) {
            return RateLimitDecision.CLOSE;
        }
        return RateLimitDecision.WARNING;
    }

    /**
     * 获取当前连续违规次数
     */
    public int getConsecutiveViolations() {
        return consecutiveViolations.get();
    }

    /**
     * 重置限流状态
     */
    public void reset() {
        currentWindowSecond.set(0);
        currentCount.set(0);
        previousCount.set(0);
        consecutiveViolations.set(0);
    }
}
