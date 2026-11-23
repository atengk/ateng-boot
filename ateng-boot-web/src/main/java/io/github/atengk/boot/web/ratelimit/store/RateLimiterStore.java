package io.github.atengk.boot.web.ratelimit.store;

import java.util.concurrent.TimeUnit;

/**
 * 接口限流状态存储与许可获取 SPI 契约接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface RateLimiterStore {

    /**
     * 尝试在指定时间窗口内获取许可（令牌）
     *
     * @param key   限流业务唯一标识键
     * @param count 时间周期内允许的最大请求数（令牌容量）
     * @param time  时间窗口大小
     * @param unit  时间单位
     * @return true 获取许可成功（未超限）；false 超过阈值（触发限流）
     */
    boolean tryAcquire(String key, long count, long time, TimeUnit unit);

    /**
     * 重置指定限流业务键的状态与配额
     *
     * @param key 限流业务唯一标识键
     * @throws IllegalArgumentException 当 key 为 null 或空白字符串时抛出
     */
    default void reset(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("RateLimiter key must not be blank");
        }
    }

    /**
     * 只读查询指定限流业务键在当前时间窗口内的剩余可用许可数（绝不扣减配额或重置窗口）
     *
     * @param key   限流业务唯一标识键
     * @param count 时间周期内允许的最大请求数（令牌容量）
     * @param time  时间窗口大小
     * @param unit  时间单位
     * @return 当前剩余可用许可数；未初始化或窗口已过期时返回完整配额 {@code count}
     * @throws IllegalArgumentException 当 key 为空白、count <= 0、time <= 0 或 unit 为 null 时抛出
     */
    default long getAvailablePermits(String key, long count, long time, TimeUnit unit) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("RateLimiter key must not be blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("RateLimiter count must be positive");
        }
        if (time <= 0) {
            throw new IllegalArgumentException("RateLimiter time must be positive");
        }
        if (unit == null) {
            throw new IllegalArgumentException("TimeUnit must not be null");
        }
        return count;
    }
}
