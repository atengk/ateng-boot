package io.github.atengk.boot.web.ratelimit.store;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 RedissonClient 的分布式接口限流存储实现
 * 使用 RRateLimiter 原生集群令牌桶
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedissonRateLimiterStore implements RateLimiterStore {

    private final RedissonClient redissonClient;

    public RedissonRateLimiterStore(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
    }

    @Override
    public boolean tryAcquire(String key, long count, long time, TimeUnit unit) {
        Assert.hasText(key, "rate limiter key must not be blank");
        Objects.requireNonNull(unit, "time unit must not be null");
        if (count <= 0 || time <= 0) {
            return true;
        }

        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.trySetRate(RateType.OVERALL, count, Duration.ofMillis(unit.toMillis(time)));
        return rateLimiter.tryAcquire(1);
    }
}
