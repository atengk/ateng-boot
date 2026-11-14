package io.github.atengk.boot.web.ratelimit.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
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

        try {
            RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
            rateLimiter.trySetRate(RateType.OVERALL, count, Duration.ofMillis(unit.toMillis(time)));
            return rateLimiter.tryAcquire(1);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redisson rate limiter tryAcquire failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void reset(String key) {
        Assert.hasText(key, "rate limiter key must not be blank");
        try {
            RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
            rateLimiter.delete();
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redisson rate limiter reset failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long getAvailablePermits(String key, long count, long time, TimeUnit unit) {
        long fullCount = RateLimiterStore.super.getAvailablePermits(key, count, time, unit);
        try {
            RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
            if (!rateLimiter.isExists()) {
                return fullCount;
            }
            long available = rateLimiter.availablePermits();
            return Math.max(0L, Math.min(fullCount, available));
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redisson rate limiter getAvailablePermits failed: " + e.getMessage(), e);
        }
    }
}
