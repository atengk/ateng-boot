package io.github.atengk.boot.redis.ratelimit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 业务级分布式并发限流客户端
 * 基于 Redisson 原生令牌桶算法（RRateLimiter）实现，支持在定时任务、异步批处理与 RPC 外部调用中提供高平滑度限流保障
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisRateLimiterClient {

    private final RedissonClient redissonClient;

    public RedisRateLimiterClient(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
    }

    /**
     * 尝试获取 1 个令牌（非阻塞，基于 Duration 窗口）
     *
     * @param key 限流键
     * @param rate 窗口速率（令牌数）
     * @param rateInterval 窗口时长
     * @return 是否成功获取令牌
     */
    public boolean tryAcquire(String key, long rate, Duration rateInterval) {
        return tryAcquire(key, rate, rateInterval, 1L);
    }

    /**
     * 尝试获取指定数量令牌（非阻塞，基于 Duration 窗口）
     *
     * @param key 限流键
     * @param rate 窗口速率（令牌数）
     * @param rateInterval 窗口时长
     * @param permits 请求令牌数量
     * @return 是否成功获取令牌
     */
    public boolean tryAcquire(String key, long rate, Duration rateInterval, long permits) {
        Assert.notNull(rateInterval, "rateInterval must not be null");
        RRateLimiter rateLimiter = getRateLimiter(key, rate, rateInterval, RateType.OVERALL);
        return rateLimiter.tryAcquire(permits);
    }

    /**
     * 尝试在指定超时时间内获取令牌（阻塞等待）
     *
     * @param key 限流键
     * @param rate 窗口速率（令牌数）
     * @param rateInterval 窗口时长
     * @param permits 请求令牌数量
     * @param timeout 最大等待超时时长
     * @return 是否在超时时间内成功获取令牌
     */
    public boolean tryAcquire(String key, long rate, Duration rateInterval, long permits, Duration timeout) {
        Assert.notNull(rateInterval, "rateInterval must not be null");
        Assert.notNull(timeout, "timeout must not be null");
        RRateLimiter rateLimiter = getRateLimiter(key, rate, rateInterval, RateType.OVERALL);
        return rateLimiter.tryAcquire(permits, timeout);
    }

    /**
     * 尝试在指定超时时间内获取令牌（兼容 TimeUnit）
     *
     * @param key 限流键
     * @param rate 窗口速率（令牌数）
     * @param rateInterval 窗口时长
     * @param permits 请求令牌数量
     * @param timeout 最大等待数值
     * @param timeUnit 超时单位
     * @return 是否在超时时间内成功获取令牌
     */
    public boolean tryAcquire(String key, long rate, Duration rateInterval, long permits, long timeout, TimeUnit timeUnit) {
        Assert.notNull(timeUnit, "timeUnit must not be null");
        return tryAcquire(key, rate, rateInterval, permits, Duration.ofMillis(timeUnit.toMillis(timeout)));
    }

    /**
     * 尝试获取 1 个令牌（兼容传统 RateIntervalUnit）
     */
    @SuppressWarnings("deprecation")
    public boolean tryAcquire(String key, long rate, long rateInterval, RateIntervalUnit intervalUnit) {
        Assert.notNull(intervalUnit, "intervalUnit must not be null");
        return tryAcquire(key, rate, Duration.ofMillis(intervalUnit.toMillis(rateInterval)), 1L);
    }

    /**
     * 尝试获取指定数量令牌（兼容传统 RateIntervalUnit）
     */
    @SuppressWarnings("deprecation")
    public boolean tryAcquire(String key, long rate, long rateInterval, RateIntervalUnit intervalUnit, long permits) {
        Assert.notNull(intervalUnit, "intervalUnit must not be null");
        return tryAcquire(key, rate, Duration.ofMillis(intervalUnit.toMillis(rateInterval)), permits);
    }

    /**
     * 阻塞获取 1 个令牌（基于 Duration 窗口）
     *
     * @param key 限流键
     * @param rate 窗口速率（令牌数）
     * @param rateInterval 窗口时长
     */
    public void acquire(String key, long rate, Duration rateInterval) {
        acquire(key, rate, rateInterval, 1L);
    }

    /**
     * 阻塞获取指定数量令牌（基于 Duration 窗口）
     *
     * @param key 限流键
     * @param rate 窗口速率（令牌数）
     * @param rateInterval 窗口时长
     * @param permits 请求令牌数量
     */
    public void acquire(String key, long rate, Duration rateInterval, long permits) {
        Assert.notNull(rateInterval, "rateInterval must not be null");
        RRateLimiter rateLimiter = getRateLimiter(key, rate, rateInterval, RateType.OVERALL);
        rateLimiter.acquire(permits);
    }

    /**
     * 阻塞获取 1 个令牌（兼容传统 RateIntervalUnit）
     */
    @SuppressWarnings("deprecation")
    public void acquire(String key, long rate, long rateInterval, RateIntervalUnit intervalUnit) {
        Assert.notNull(intervalUnit, "intervalUnit must not be null");
        acquire(key, rate, Duration.ofMillis(intervalUnit.toMillis(rateInterval)), 1L);
    }

    /**
     * 阻塞获取指定数量令牌（兼容传统 RateIntervalUnit）
     */
    @SuppressWarnings("deprecation")
    public void acquire(String key, long rate, long rateInterval, RateIntervalUnit intervalUnit, long permits) {
        Assert.notNull(intervalUnit, "intervalUnit must not be null");
        acquire(key, rate, Duration.ofMillis(intervalUnit.toMillis(rateInterval)), permits);
    }

    /**
     * 获取当前可用剩余令牌数
     *
     * @param key 限流键
     * @return 可用令牌数
     */
    public long getAvailablePermits(String key) {
        Assert.hasText(key, "key must not be blank");
        return redissonClient.getRateLimiter(key).availablePermits();
    }

    /**
     * 删除限流器状态配置
     *
     * @param key 限流键
     * @return 是否成功删除
     */
    public boolean delete(String key) {
        Assert.hasText(key, "key must not be blank");
        return redissonClient.getRateLimiter(key).delete();
    }

    /**
     * 获取或初始化底层 RRateLimiter 实例（基于 Duration）
     *
     * @param key 限流键
     * @param rate 窗口速率
     * @param rateInterval 时间区间 Duration
     * @param rateType 限流模式
     * @return RRateLimiter 实例
     */
    public RRateLimiter getRateLimiter(String key, long rate, Duration rateInterval, RateType rateType) {
        Assert.hasText(key, "key must not be blank");
        Assert.isTrue(rate > 0, "rate must be greater than 0");
        Assert.notNull(rateInterval, "rateInterval must not be null");
        Assert.isTrue(!rateInterval.isZero() && !rateInterval.isNegative(), "rateInterval must be positive");
        Assert.notNull(rateType, "rateType must not be null");

        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.trySetRate(rateType, rate, rateInterval);
        return rateLimiter;
    }

    /**
     * 获取或初始化底层 RRateLimiter 实例（兼容传统 RateIntervalUnit）
     */
    @SuppressWarnings("deprecation")
    public RRateLimiter getRateLimiter(String key, long rate, long rateInterval,
                                       RateIntervalUnit intervalUnit, RateType rateType) {
        Assert.notNull(intervalUnit, "intervalUnit must not be null");
        return getRateLimiter(key, rate, Duration.ofMillis(intervalUnit.toMillis(rateInterval)), rateType);
    }
}
