package io.github.atengk.boot.web.idempotent.store;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 RedissonClient 的分布式接口幂等键存储实现
 * 使用 RBucket.setIfAbsent 原子抢占并设置超时存活期
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedissonIdempotentKeyStore implements IdempotentKeyStore {

    /**
     * 幂等加锁占位标识值
     */
    private static final String LOCK_VALUE = "1";

    private final RedissonClient redissonClient;

    /**
     * 构造基于 RedissonClient 的幂等存储
     *
     * @param redissonClient Redisson 客户端
     */
    public RedissonIdempotentKeyStore(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
    }

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Assert.hasText(key, "idempotent key must not be blank");
        Objects.requireNonNull(unit, "time unit must not be null");
        RBucket<String> bucket = redissonClient.getBucket(key);
        return bucket.setIfAbsent(LOCK_VALUE, Duration.ofMillis(unit.toMillis(timeout)));
    }

    @Override
    public void unlock(String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.delete();
    }
}
